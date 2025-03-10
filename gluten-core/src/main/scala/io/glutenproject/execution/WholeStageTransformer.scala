/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.glutenproject.execution

import io.glutenproject.{GlutenConfig, GlutenNumaBindingInfo}
import io.glutenproject.backendsapi.BackendsApiManager
import io.glutenproject.exception.GlutenException
import io.glutenproject.expression._
import io.glutenproject.extension.GlutenPlan
import io.glutenproject.metrics.{GlutenTimeMetric, MetricsUpdater, NoopMetricsUpdater}
import io.glutenproject.substrait.`type`.{TypeBuilder, TypeNode}
import io.glutenproject.substrait.SubstraitContext
import io.glutenproject.substrait.plan.{PlanBuilder, PlanNode}
import io.glutenproject.substrait.rel.RelNode
import io.glutenproject.utils.SubstraitPlanPrinterUtil

import org.apache.spark.{Dependency, OneToOneDependency, Partition, SparkConf, TaskContext}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Attribute, SortOrder}
import org.apache.spark.sql.catalyst.plans.physical.Partitioning
import org.apache.spark.sql.execution._
import org.apache.spark.sql.execution.metric.SQLMetric
import org.apache.spark.sql.vectorized.ColumnarBatch

import com.google.common.collect.Lists

import scala.collection.mutable

case class TransformContext(
    inputAttributes: Seq[Attribute],
    outputAttributes: Seq[Attribute],
    root: RelNode)

case class WholeStageTransformContext(root: PlanNode, substraitContext: SubstraitContext = null)

trait TransformSupport extends GlutenPlan {

  final override def doExecute(): RDD[InternalRow] = {
    throw new UnsupportedOperationException(
      s"${this.getClass.getSimpleName} doesn't support doExecute")
  }

  final override lazy val supportsColumnar: Boolean = true

  /**
   * Returns all the RDDs of ColumnarBatch which generates the input rows.
   *
   * @note
   *   Right now we support up to two RDDs
   */
  def columnarInputRDDs: Seq[RDD[ColumnarBatch]]

  def doTransform(context: SubstraitContext): TransformContext = {
    throw new UnsupportedOperationException(
      s"This operator doesn't support doTransform with SubstraitContext.")
  }

  def metricsUpdater(): MetricsUpdater

  protected def getColumnarInputRDDs(plan: SparkPlan): Seq[RDD[ColumnarBatch]] = {
    plan match {
      case c: TransformSupport =>
        c.columnarInputRDDs
      case _ =>
        Seq(plan.executeColumnar())
    }
  }
}

trait LeafTransformSupport extends TransformSupport with LeafExecNode {
  final override def columnarInputRDDs: Seq[RDD[ColumnarBatch]] = Seq.empty
}

trait UnaryTransformSupport extends TransformSupport with UnaryExecNode {
  final override def columnarInputRDDs: Seq[RDD[ColumnarBatch]] = {
    getColumnarInputRDDs(child)
  }
}

case class WholeStageTransformer(child: SparkPlan, materializeInput: Boolean = false)(
    val transformStageId: Int
) extends UnaryTransformSupport {
  assert(child.isInstanceOf[TransformSupport])

  // For WholeStageCodegen-like operator, only pipeline time will be handled in graph plotting.
  // See SparkPlanGraph.scala:205 for reference.
  // Note: "metrics" is made transient to avoid sending driver-side metrics to tasks.
  @transient override lazy val metrics: Map[String, SQLMetric] =
    BackendsApiManager.getMetricsApiInstance.genWholeStageTransformerMetrics(sparkContext)

  val sparkConf: SparkConf = sparkContext.getConf
  val numaBindingInfo: GlutenNumaBindingInfo = GlutenConfig.getConf.numaBindingInfo
  val substraitPlanLogLevel: String = GlutenConfig.getConf.substraitPlanLogLevel

  private var planJson: String = ""

  def getPlanJson: String = {
    if (log.isDebugEnabled() && planJson.isEmpty) {
      logWarning("Plan in JSON string is empty. This may due to the plan has not been executed.")
    }
    planJson
  }

  override def output: Seq[Attribute] = child.output

  override def outputPartitioning: Partitioning = child.outputPartitioning

  override def outputOrdering: Seq[SortOrder] = child.outputOrdering

  override def otherCopyArgs: Seq[AnyRef] = Seq(transformStageId.asInstanceOf[Integer])

  override def generateTreeString(
      depth: Int,
      lastChildren: Seq[Boolean],
      append: String => Unit,
      verbose: Boolean,
      prefix: String = "",
      addSuffix: Boolean = false,
      maxFields: Int,
      printNodeId: Boolean,
      indent: Int = 0): Unit = {
    val prefix = if (printNodeId) "^ " else s"^($transformStageId) "
    child.generateTreeString(
      depth,
      lastChildren,
      append,
      verbose,
      prefix,
      addSuffix = false,
      maxFields,
      printNodeId = printNodeId,
      indent)
    if (verbose && planJson.nonEmpty) {
      append(prefix + "Substrait plan:\n")
      append(planJson)
      append("\n")
    }
  }

  // It's misleading with "Codegen" used. But we have to keep "WholeStageCodegen" prefixed to
  // make whole stage transformer clearly plotted in UI, like spark's whole stage codegen.
  // See buildSparkPlanGraphNode in SparkPlanGraph.scala of Spark.
  override def nodeName: String = s"WholeStageCodegenTransformer ($transformStageId)"

  def doWholeStageTransform(): WholeStageTransformContext = {
    // invoke SparkPlan.prepare to do subquery preparation etc.
    super.prepare()

    val substraitContext = new SubstraitContext
    val childCtx = child
      .asInstanceOf[TransformSupport]
      .doTransform(substraitContext)
    if (childCtx == null) {
      throw new NullPointerException(s"WholeStageTransformer can't do Transform on $child")
    }
    val outNames = new java.util.ArrayList[String]()
    val planNode = if (BackendsApiManager.getSettings.needOutputSchemaForPlan()) {
      val outputTypeNodeList = new java.util.ArrayList[TypeNode]()
      for (attr <- childCtx.outputAttributes) {
        outNames.add(ConverterUtils.genColumnNameWithExprId(attr))
        outputTypeNodeList.add(ConverterUtils.getTypeNode(attr.dataType, attr.nullable))
      }

      // Fixes issue-1874
      val outputSchema = TypeBuilder.makeStruct(false, outputTypeNodeList)
      PlanBuilder.makePlan(
        substraitContext,
        Lists.newArrayList(childCtx.root),
        outNames,
        outputSchema,
        null)
    } else {
      for (attr <- childCtx.outputAttributes) {
        outNames.add(ConverterUtils.genColumnNameWithExprId(attr))
      }
      PlanBuilder.makePlan(substraitContext, Lists.newArrayList(childCtx.root), outNames)
    }

    if (log.isDebugEnabled()) {
      planJson = SubstraitPlanPrinterUtil.substraitPlanToJson(planNode.toProtobuf)
    }

    WholeStageTransformContext(planNode, substraitContext)
  }

  /** Find all BasicScanExecTransformers in one WholeStageTransformer */
  private def findAllScanTransformers(): Seq[BasicScanExecTransformer] = {
    val basicScanExecTransformers = new mutable.ListBuffer[BasicScanExecTransformer]()

    def transformChildren(
        plan: SparkPlan,
        basicScanExecTransformers: mutable.ListBuffer[BasicScanExecTransformer]): Unit = {
      if (plan != null && plan.isInstanceOf[TransformSupport]) {
        plan match {
          case transformer: BasicScanExecTransformer =>
            basicScanExecTransformers.append(transformer)
          case _ =>
        }
        // according to the substrait plan order
        // SHJ may include two scans in a whole stage.
        plan match {
          case shj: HashJoinLikeExecTransformer =>
            transformChildren(shj.streamedPlan, basicScanExecTransformers)
            transformChildren(shj.buildPlan, basicScanExecTransformers)
          case t: TransformSupport =>
            t.children
              .foreach(transformChildren(_, basicScanExecTransformers))
        }
      }
    }

    transformChildren(child, basicScanExecTransformers)
    basicScanExecTransformers
  }

  override def doExecuteColumnar(): RDD[ColumnarBatch] = {
    val pipelineTime: SQLMetric = longMetric("pipelineTime")
    val inputRDDs = new ColumnarInputRDDsWrapper(columnarInputRDDs)
    // Check if BatchScan exists.
    val basicScanExecTransformers = findAllScanTransformers()

    if (basicScanExecTransformers.nonEmpty) {

      /**
       * If containing scan exec transformer this "whole stage" generates a RDD which itself takes
       * care of SCAN there won't be any other RDD for SCAN. As a result, genFirstStageIterator
       * rather than genFinalStageIterator will be invoked
       */

      // If these are two scan transformers, they must have same partitions,
      // otherwise, exchange will be inserted.
      val allScanPartitions = basicScanExecTransformers.map(_.getPartitions)
      val allScanPartitionSchemas = basicScanExecTransformers.map(_.getPartitionSchemas)
      val partitionLength = allScanPartitions.head.size
      if (allScanPartitions.exists(_.size != partitionLength)) {
        throw new GlutenException(
          "The partition length of all the scan transformer are not the same.")
      }
      val (wsCxt, substraitPlanPartitions) = GlutenTimeMetric.withMillisTime {
        val wsCxt = doWholeStageTransform()

        // the file format for each scan exec
        val fileFormats = basicScanExecTransformers.map(ConverterUtils.getFileFormat)

        // generate each partition of all scan exec
        val substraitPlanPartitions = (0 until partitionLength).map(
          i => {
            val currentPartitions = allScanPartitions.map(_(i))
            BackendsApiManager.getIteratorApiInstance
              .genFilePartition(i, currentPartitions, allScanPartitionSchemas, fileFormats, wsCxt)
          })
        (wsCxt, substraitPlanPartitions)
      }(
        t =>
          logOnLevel(
            substraitPlanLogLevel,
            s"$nodeName generating the substrait plan took: $t ms."))

      new GlutenWholeStageColumnarRDD(
        sparkContext,
        substraitPlanPartitions,
        inputRDDs,
        pipelineTime,
        leafMetricsUpdater().updateInputMetrics,
        BackendsApiManager.getMetricsApiInstance.metricsUpdatingFunction(
          child,
          wsCxt.substraitContext.registeredRelMap,
          wsCxt.substraitContext.registeredJoinParams,
          wsCxt.substraitContext.registeredAggregationParams
        )
      )
    } else {
      val buildRelationBatchHolder: mutable.ListBuffer[ColumnarBatch] = mutable.ListBuffer()

      /**
       * the whole stage contains NO BasicScanExecTransformer. this the default case for:
       *   1. SCAN with clickhouse backend (check ColumnarCollapseTransformStages#separateScanRDD())
       *      2. test case where query plan is constructed from simple dataframes (e.g.
       *      GlutenDataFrameAggregateSuite) in these cases, separate RDDs takes care of SCAN as a
       *      result, genFinalStageIterator rather than genFirstStageIterator will be invoked
       */
      val resCtx = GlutenTimeMetric.withMillisTime(doWholeStageTransform()) {
        t =>
          logOnLevel(substraitPlanLogLevel, s"$nodeName generating the substrait plan took: $t ms.")
      }
      new WholeStageZippedPartitionsRDD(
        sparkContext,
        inputRDDs,
        numaBindingInfo,
        sparkConf,
        resCtx,
        pipelineTime,
        buildRelationBatchHolder,
        BackendsApiManager.getMetricsApiInstance.metricsUpdatingFunction(
          child,
          resCtx.substraitContext.registeredRelMap,
          resCtx.substraitContext.registeredJoinParams,
          resCtx.substraitContext.registeredAggregationParams
        ),
        materializeInput
      )
    }
  }

  override def metricsUpdater(): MetricsUpdater = {
    child match {
      case transformer: TransformSupport => transformer.metricsUpdater()
      case _ => NoopMetricsUpdater
    }
  }

  private def leafMetricsUpdater(): MetricsUpdater = {
    child
      .find {
        case t: TransformSupport if t.children.forall(!_.isInstanceOf[TransformSupport]) => true
        case _ => false
      }
      .map(_.asInstanceOf[TransformSupport].metricsUpdater())
      .getOrElse(NoopMetricsUpdater)
  }

  override protected def withNewChildInternal(newChild: SparkPlan): WholeStageTransformer =
    copy(child = newChild, materializeInput = materializeInput)(transformStageId)
}

/**
 * This `columnarInputRDDs` would contain [[BroadcastBuildSideRDD]], but the dependency and
 * partition of [[BroadcastBuildSideRDD]] is meaningless. [[BroadcastBuildSideRDD]] should only be
 * used to hold the broadcast value and generate iterator for join.
 */
class ColumnarInputRDDsWrapper(columnarInputRDDs: Seq[RDD[ColumnarBatch]]) extends Serializable {
  def getDependencies: Seq[Dependency[ColumnarBatch]] = {
    assert(
      columnarInputRDDs
        .filterNot(_.isInstanceOf[BroadcastBuildSideRDD])
        .map(_.partitions.length)
        .toSet
        .size <= 1)

    columnarInputRDDs.flatMap {
      case _: BroadcastBuildSideRDD => Nil
      case rdd => new OneToOneDependency[ColumnarBatch](rdd) :: Nil
    }
  }

  def getPartitions(index: Int): Seq[Partition] = {
    columnarInputRDDs.filterNot(_.isInstanceOf[BroadcastBuildSideRDD]).map(_.partitions(index))
  }

  def getPartitionLength: Int = {
    assert(columnarInputRDDs.nonEmpty)
    val nonBroadcastRDD = columnarInputRDDs.find(!_.isInstanceOf[BroadcastBuildSideRDD])
    assert(nonBroadcastRDD.isDefined)
    nonBroadcastRDD.get.partitions.length
  }

  def getIterators(
      inputColumnarRDDPartitions: Seq[Partition],
      context: TaskContext): Seq[Iterator[ColumnarBatch]] = {
    var index = 0
    columnarInputRDDs.map {
      case broadcast: BroadcastBuildSideRDD =>
        BackendsApiManager.getIteratorApiInstance
          .genBroadcastBuildSideIterator(broadcast.broadcasted, broadcast.broadCastContext)
      case rdd =>
        val it = rdd.iterator(inputColumnarRDDPartitions(index), context)
        index += 1
        it
    }
  }
}
