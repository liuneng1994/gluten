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
#pragma once

#include "config.h"

#if USE_PARQUET
#include <memory>
#include <DataTypes/DataTypeFixedString.h>
#include <DataTypes/DataTypeString.h>
#include <DataTypes/DataTypesNumber.h>
#include <IO/ReadBuffer.h>
#include <Storages/SubstraitSource/FormatFile.h>
#include <parquet/metadata.h>
#include <parquet/statistics.h>

namespace local_engine
{
struct RowGroupInfomation
{
    UInt32 index = 0;
    UInt64 start = 0;
    UInt64 total_compressed_size = 0;
    UInt64 total_size = 0;
    UInt64 num_rows = 0;
};
class ParquetFormatFile : public FormatFile
{
public:
    explicit ParquetFormatFile(
        DB::ContextPtr context_,
        const substrait::ReadRel::LocalFiles::FileOrFiles & file_info_,
        ReadBufferBuilderPtr read_buffer_builder_,
        bool useLocalFormat);
    ~ParquetFormatFile() override = default;

    FormatFile::InputFormatPtr createInputFormat(const DB::Block & header) override;
    std::optional<size_t> getTotalRows() override;

    bool supportSplit() const override { return true; }

    DB::String getFileFormat() const override { return "Parquet"; }

private:
    bool use_local_format;
    std::mutex mutex;
    std::optional<size_t> total_rows;

    std::vector<RowGroupInfomation> collectRequiredRowGroups(int & total_row_groups);
    std::vector<RowGroupInfomation> collectRequiredRowGroups(DB::ReadBuffer * read_buffer, int & total_row_groups);
};

}
#endif
