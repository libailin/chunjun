/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dtstack.chunjun.connector.s3.converter;

import com.dtstack.chunjun.config.CommonConfig;
import com.dtstack.chunjun.converter.AbstractRowConverter;
import com.dtstack.chunjun.converter.IDeserializationConverter;
import com.dtstack.chunjun.converter.ISerializationConverter;
import com.dtstack.chunjun.throwable.UnsupportedTypeException;
import com.dtstack.chunjun.util.DateUtil;

import org.apache.flink.table.data.DecimalData;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.StringData;
import org.apache.flink.table.data.TimestampData;
import org.apache.flink.table.types.logical.DecimalType;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.types.logical.RowType;
import org.apache.flink.table.types.logical.TimestampType;

import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public class S3SqlConverter extends AbstractRowConverter<String[], RowData, String[], LogicalType> {

    private static final long serialVersionUID = 4835129977890244317L;

    public S3SqlConverter(RowType rowType, CommonConfig conf) {
        super(rowType, conf);
        for (int i = 0; i < rowType.getFieldCount(); i++) {
            toInternalConverters.add(
                    wrapIntoNullableInternalConverter(
                            createInternalConverter(rowType.getTypeAt(i))));
            toExternalConverters.add(
                    wrapIntoNullableExternalConverter(
                            createExternalConverter(fieldTypes[i]), fieldTypes[i]));
        }
    }

    @Override
    public RowData toInternal(String[] input) throws Exception {
        GenericRowData rowData = new GenericRowData(input.length);
        for (int i = 0; i < fieldTypes.length; i++) {
            rowData.setField(i, toInternalConverters.get(i).deserialize(input[i]));
        }
        return rowData;
    }

    @Override
    public String[] toExternal(RowData rowData, String[] output) throws Exception {
        output = new String[fieldTypes.length];
        for (int i = 0; i < fieldTypes.length; i++) {
            toExternalConverters.get(i).serialize(rowData, i, output);
        }
        return output;
    }

    @Override
    protected IDeserializationConverter createInternalConverter(LogicalType type) {
        switch (type.getTypeRoot()) {
            case NULL:
                return val -> null;
            case INTEGER:
                return val -> Integer.valueOf((String) val);
            case BIGINT:
                return val -> Long.valueOf((String) val);
            case FLOAT:
                return val -> Float.valueOf((String) val);
            case DOUBLE:
                return val -> Double.valueOf((String) val);
            case DECIMAL:
                final int precision = ((DecimalType) type).getPrecision();
                final int scale = ((DecimalType) type).getScale();
                return val ->
                        val instanceof BigInteger
                                ? DecimalData.fromBigDecimal(
                                        new BigDecimal((BigInteger) val, 0), precision, scale)
                                : DecimalData.fromBigDecimal(
                                        StringUtils.isNotEmpty(String.valueOf(val))
                                                ? new BigDecimal(String.valueOf(val))
                                                : BigDecimal.ZERO,
                                        precision,
                                        scale);
            case TIMESTAMP_WITH_TIME_ZONE:
            case TIMESTAMP_WITHOUT_TIME_ZONE:
                return val -> {
                    if (val instanceof String) {
                        return TimestampData.fromTimestamp(Timestamp.valueOf((String) val));
                    } else if (val instanceof LocalDateTime) {
                        return TimestampData.fromTimestamp(Timestamp.valueOf((LocalDateTime) val));
                    } else {
                        return TimestampData.fromTimestamp(((Timestamp) val));
                    }
                };
            case CHAR:
            case VARCHAR:
                return val -> StringData.fromString((String) val);
            case DATE:
                return val -> {
                    if (StringUtils.isEmpty(String.valueOf(val))) {
                        return null;
                    }
                    Date date = new Date(DateUtil.stringToDate(String.valueOf(val)).getTime());
                    return (int) date.toLocalDate().toEpochDay();
                };
            case TIME_WITHOUT_TIME_ZONE:
                return val ->
                        (int)
                                ((Time.valueOf(String.valueOf(val))).toLocalTime().toNanoOfDay()
                                        / 1_000_000L);
            default:
                throw new UnsupportedOperationException(type.toString());
        }
    }

    @Override
    protected ISerializationConverter<String[]> createExternalConverter(LogicalType type) {
        switch (type.getTypeRoot()) {
            case NULL:
                return (val, index, output) -> output[index] = null;
            case INTEGER:
                return (val, index, output) -> output[index] = String.valueOf(val.getInt(index));
            case BIGINT:
                return (val, index, output) -> output[index] = String.valueOf(val.getLong(index));
            case FLOAT:
                return (val, index, output) -> output[index] = String.valueOf(val.getFloat(index));
            case DOUBLE:
                return (val, index, output) -> output[index] = String.valueOf(val.getDouble(index));
            case CHAR:
            case VARCHAR:
                return (val, index, output) ->
                        output[index] = String.valueOf(val.getString(index).toString());
            case DATE:
                return (val, index, output) ->
                        output[index] =
                                Date.valueOf(LocalDate.ofEpochDay(val.getInt(index))).toString();
            case TIME_WITHOUT_TIME_ZONE:
                return (val, index, output) ->
                        output[index] =
                                Time.valueOf(LocalTime.ofNanoOfDay(val.getInt(index) * 1_000_000L))
                                        .toString();
            case DECIMAL:
                return (rowData, index, data) ->
                        data[index] =
                                String.valueOf(
                                        rowData.getDecimal(
                                                index,
                                                ((DecimalType) type).getPrecision(),
                                                ((DecimalType) type).getScale()));
            case TIMESTAMP_WITHOUT_TIME_ZONE:
                return (rowData, index, data) ->
                        data[index] =
                                String.valueOf(
                                        rowData.getTimestamp(
                                                        index,
                                                        ((TimestampType) type).getPrecision())
                                                .toTimestamp());
            default:
                throw new UnsupportedTypeException(type.toString());
        }
    }

    @Override
    protected ISerializationConverter<String[]> wrapIntoNullableExternalConverter(
            ISerializationConverter<String[]> converter, LogicalType type) {
        return converter;
    }
}
