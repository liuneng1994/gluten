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

import org.apache.spark.SparkConf

class VeloxAggregateFunctionsSuite extends VeloxWholeStageTransformerSuite {

  protected val rootPath: String = getClass.getResource("/").getPath
  override protected val backend: String = "velox"
  override protected val resourcePath: String = "/tpch-data-parquet-velox"
  override protected val fileFormat: String = "parquet"

  override def beforeAll(): Unit = {
    super.beforeAll()
    createTPCHNotNullTables()
  }

  override protected def sparkConf: SparkConf = {
    super.sparkConf
      .set("spark.shuffle.manager", "org.apache.spark.shuffle.sort.ColumnarShuffleManager")
      .set("spark.sql.files.maxPartitionBytes", "1g")
      .set("spark.sql.shuffle.partitions", "1")
      .set("spark.memory.offHeap.size", "2g")
      .set("spark.unsafe.exceptionOnMemoryLeak", "true")
      .set("spark.sql.autoBroadcastJoinThreshold", "-1")
      .set("spark.sql.sources.useV1SourceList", "avro")
  }

  test("count") {
    val df =
      runQueryAndCompare("select count(*) from lineitem where l_partkey in (1552, 674, 1062)") {
        checkOperatorMatch[HashAggregateExecTransformer]
      }
    runQueryAndCompare("select count(l_quantity), count(distinct l_partkey) from lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
  }

  test("avg") {
    val df = runQueryAndCompare("select avg(l_partkey) from lineitem where l_partkey < 1000") {
      checkOperatorMatch[HashAggregateExecTransformer]
    }
    runQueryAndCompare("select avg(l_quantity), count(distinct l_partkey) from lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
    runQueryAndCompare(
      "select avg(cast (l_quantity as DECIMAL(12, 2))), " +
        "count(distinct l_partkey) from lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
    runQueryAndCompare(
      "select avg(cast (l_quantity as DECIMAL(22, 2))), " +
        "count(distinct l_partkey) from lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
  }

  test("sum") {
    runQueryAndCompare("select sum(l_partkey) from lineitem where l_partkey < 2000") {
      checkOperatorMatch[HashAggregateExecTransformer]
    }
    runQueryAndCompare("select sum(l_quantity), count(distinct l_partkey) from lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
    runQueryAndCompare("select sum(cast (l_quantity as DECIMAL(22, 2))) from lineitem") {
      checkOperatorMatch[HashAggregateExecTransformer]
    }
    runQueryAndCompare(
      "select sum(cast (l_quantity as DECIMAL(12, 2))), " +
        "count(distinct l_partkey) from lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
    runQueryAndCompare(
      "select sum(cast (l_quantity as DECIMAL(22, 2))), " +
        "count(distinct l_partkey) from lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
  }

  test("min and max") {
    runQueryAndCompare(
      "select min(l_partkey), max(l_partkey) from lineitem where l_partkey < 2000") {
      checkOperatorMatch[HashAggregateExecTransformer]
    }
    runQueryAndCompare(
      "select min(l_partkey), max(l_partkey), count(distinct l_partkey) from lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
  }

  test("groupby") {
    val df = runQueryAndCompare(
      "select l_orderkey, sum(l_partkey) as sum from lineitem " +
        "where l_orderkey < 3 group by l_orderkey") { _ => }
    checkLengthAndPlan(df, 2)
  }

  test("group sets") {
    val result = runQueryAndCompare(
      "select l_orderkey, l_partkey, sum(l_suppkey) from lineitem " +
        "where l_orderkey < 3 group by ROLLUP(l_orderkey, l_partkey) " +
        "order by l_orderkey, l_partkey ") { _ => }
  }

  test("stddev_samp") {
    runQueryAndCompare("""
                         |select stddev_samp(l_quantity) from lineitem;
                         |""".stripMargin) {
      checkOperatorMatch[HashAggregateExecTransformer]
    }
    runQueryAndCompare("""
                         |select l_orderkey, stddev_samp(l_quantity) from lineitem
                         |group by l_orderkey;
                         |""".stripMargin) {
      checkOperatorMatch[HashAggregateExecTransformer]
    }
    runQueryAndCompare("select stddev_samp(l_quantity), count(distinct l_partkey) from lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
  }

  test("stddev_pop") {
    runQueryAndCompare("""
                         |select stddev_pop(l_quantity) from lineitem;
                         |""".stripMargin) {
      checkOperatorMatch[HashAggregateExecTransformer]
    }
    runQueryAndCompare("""
                         |select l_orderkey, stddev_pop(l_quantity) from lineitem
                         |group by l_orderkey;
                         |""".stripMargin) {
      checkOperatorMatch[HashAggregateExecTransformer]
    }
    runQueryAndCompare("select stddev_pop(l_quantity), count(distinct l_partkey) from lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
  }

  test("var_samp") {
    runQueryAndCompare("""
                         |select var_samp(l_quantity) from lineitem;
                         |""".stripMargin) {
      checkOperatorMatch[HashAggregateExecTransformer]
    }
    runQueryAndCompare("""
                         |select l_orderkey, var_samp(l_quantity) from lineitem
                         |group by l_orderkey;
                         |""".stripMargin) {
      checkOperatorMatch[HashAggregateExecTransformer]
    }
    runQueryAndCompare("select var_samp(l_quantity), count(distinct l_partkey) from lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
  }

  test("var_pop") {
    runQueryAndCompare("""
                         |select var_pop(l_quantity) from lineitem;
                         |""".stripMargin) {
      checkOperatorMatch[HashAggregateExecTransformer]
    }
    runQueryAndCompare("""
                         |select l_orderkey, var_pop(l_quantity) from lineitem
                         |group by l_orderkey;
                         |""".stripMargin) {
      checkOperatorMatch[HashAggregateExecTransformer]
    }
    runQueryAndCompare("select var_pop(l_quantity), count(distinct l_partkey) from lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
  }

  test("bit_and bit_or bit_xor") {
    val bitAggs = Seq("bit_and", "bit_or", "bit_xor")
    for (func <- bitAggs) {
      runQueryAndCompare(s"""
                            |select $func(l_linenumber) from lineitem
                            |group by l_orderkey;
                            |""".stripMargin) {
        checkOperatorMatch[HashAggregateExecTransformer]
      }
      runQueryAndCompare(s"select $func(l_linenumber), count(distinct l_partkey) from lineitem") {
        df =>
          {
            assert(
              getExecutedPlan(df).count(
                plan => {
                  plan.isInstanceOf[HashAggregateExecTransformer]
                }) == 4)
          }
      }
    }
  }

  test("corr covar_pop covar_samp") {
    runQueryAndCompare("""
                         |select corr(l_partkey, l_suppkey) from lineitem;
                         |""".stripMargin) {
      checkOperatorMatch[HashAggregateExecTransformer]
    }
    runQueryAndCompare(
      "select corr(l_partkey, l_suppkey), count(distinct l_orderkey) from lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
    runQueryAndCompare("""
                         |select covar_pop(l_partkey, l_suppkey) from lineitem;
                         |""".stripMargin) {
      checkOperatorMatch[HashAggregateExecTransformer]
    }
    runQueryAndCompare(
      "select covar_pop(l_partkey, l_suppkey), count(distinct l_orderkey) from lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
    runQueryAndCompare("""
                         |select covar_samp(l_partkey, l_suppkey) from lineitem;
                         |""".stripMargin) {
      checkOperatorMatch[HashAggregateExecTransformer]
    }
    runQueryAndCompare(
      "select covar_samp(l_partkey, l_suppkey), count(distinct l_orderkey) from lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
  }

  test("first") {
    runQueryAndCompare(s"""
                          |select first(l_linenumber), first(l_linenumber, true) from lineitem;
                          |""".stripMargin) {
      checkOperatorMatch[HashAggregateExecTransformer]
    }
    runQueryAndCompare(
      s"""
         |select first_value(l_linenumber), first_value(l_linenumber, true) from lineitem
         |group by l_orderkey;
         |""".stripMargin) {
      checkOperatorMatch[HashAggregateExecTransformer]
    }
    runQueryAndCompare(
      s"""
         |select first(l_linenumber), first(l_linenumber, true), count(distinct l_partkey)
         |from lineitem
         |""".stripMargin) {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
  }

  test("last") {
    runQueryAndCompare(s"""
                          |select last(l_linenumber), last(l_linenumber, true) from lineitem;
                          |""".stripMargin) {
      checkOperatorMatch[HashAggregateExecTransformer]
    }
    runQueryAndCompare(s"""
                          |select last_value(l_linenumber), last_value(l_linenumber, true)
                          |from lineitem
                          |group by l_orderkey;
                          |""".stripMargin) {
      checkOperatorMatch[HashAggregateExecTransformer]
    }
    runQueryAndCompare(
      s"""
         |select last(l_linenumber), last(l_linenumber, true), count(distinct l_partkey)
         |from lineitem
         |""".stripMargin) {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
  }

  test("approx_count_distinct") {
    runQueryAndCompare("""
                         |select approx_count_distinct(l_shipmode) from lineitem;
                         |""".stripMargin) {
      checkOperatorMatch[HashAggregateExecTransformer]
    }
    runQueryAndCompare(
      "select approx_count_distinct(l_partkey), count(distinct l_orderkey) from lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 0)
        }
    }
  }

  test("max_by") {
    runQueryAndCompare(s"""
                          |select max_by(l_linenumber, l_comment) from lineitem;
                          |""".stripMargin) {
      checkOperatorMatch[HashAggregateExecTransformer]
    }
    runQueryAndCompare(s"""
                          |select max_by(distinct l_linenumber, l_comment)
                          |from lineitem
                          |""".stripMargin) {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
  }

  test("min_by") {
    runQueryAndCompare(s"""
                          |select min_by(l_linenumber, l_comment) from lineitem;
                          |""".stripMargin) {
      checkOperatorMatch[HashAggregateExecTransformer]
    }
    runQueryAndCompare(s"""
                          |select min_by(distinct l_linenumber, l_comment)
                          |from lineitem
                          |""".stripMargin) {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
  }

  test("distinct functions") {
    runQueryAndCompare("SELECT sum(DISTINCT l_partkey), count(*) FROM lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
    runQueryAndCompare("SELECT sum(DISTINCT l_partkey), count(*), sum(l_partkey) FROM lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
    runQueryAndCompare("SELECT avg(DISTINCT l_partkey), count(*) FROM lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
    runQueryAndCompare("SELECT avg(DISTINCT l_partkey), count(*), avg(l_partkey) FROM lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
    runQueryAndCompare("SELECT count(DISTINCT l_partkey), count(*) FROM lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
    runQueryAndCompare(
      "SELECT count(DISTINCT l_partkey), count(*), count(l_partkey) FROM lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
    runQueryAndCompare("SELECT stddev_samp(DISTINCT l_partkey), count(*) FROM lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
    runQueryAndCompare(
      "SELECT stddev_samp(DISTINCT l_partkey), count(*), " +
        "stddev_samp(l_partkey) FROM lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
    runQueryAndCompare("SELECT stddev_pop(DISTINCT l_partkey), count(*) FROM lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
    runQueryAndCompare(
      "SELECT stddev_pop(DISTINCT l_partkey), count(*), " +
        "stddev_pop(l_partkey) FROM lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
    runQueryAndCompare("SELECT var_samp(DISTINCT l_partkey), count(*) FROM lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
    runQueryAndCompare(
      "SELECT var_samp(DISTINCT l_partkey), count(*), " +
        "var_samp(l_partkey) FROM lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
    runQueryAndCompare("SELECT var_pop(DISTINCT l_partkey), count(*) FROM lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
    runQueryAndCompare(
      "SELECT var_pop(DISTINCT l_partkey), count(*), " +
        "var_pop(l_partkey) FROM lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
    runQueryAndCompare(
      "SELECT corr(DISTINCT l_partkey, l_suppkey)," +
        "corr(DISTINCT l_suppkey, l_partkey), count(*) FROM lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
    runQueryAndCompare(
      "SELECT corr(DISTINCT l_partkey, l_suppkey)," +
        "count(*), corr(l_suppkey, l_partkey) FROM lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
    runQueryAndCompare(
      "SELECT covar_pop(DISTINCT l_partkey, l_suppkey)," +
        "covar_pop(DISTINCT l_suppkey, l_partkey), count(*) FROM lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
    runQueryAndCompare(
      "SELECT covar_pop(DISTINCT l_partkey, l_suppkey)," +
        "count(*), covar_pop(l_suppkey, l_partkey) FROM lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
    runQueryAndCompare(
      "SELECT covar_samp(DISTINCT l_partkey, l_suppkey)," +
        "covar_samp(DISTINCT l_suppkey, l_partkey), count(*) FROM lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
    runQueryAndCompare(
      "SELECT covar_samp(DISTINCT l_partkey, l_suppkey)," +
        "count(*), covar_samp(l_suppkey, l_partkey) FROM lineitem") {
      df =>
        {
          assert(
            getExecutedPlan(df).count(
              plan => {
                plan.isInstanceOf[HashAggregateExecTransformer]
              }) == 4)
        }
    }
  }

  test("count(1)") {
    runQueryAndCompare(
      """
        |select count(1) from (select * from values(1,2) as data(a,b) group by a,b union all
        |select * from values(2,3),(3,4) as data(c,d) group by c,d);
        |""".stripMargin) {
      df =>
        assert(
          getExecutedPlan(df).count(plan => plan.isInstanceOf[HashAggregateExecTransformer]) >= 2)
    }
  }
}
