package com.lanysec.services;

import com.alibaba.fastjson.JSON;
import com.lanysec.config.JavaKafkaConfigurer;
import com.lanysec.config.ModelParamsConfigurer;
import com.lanysec.entity.FlowEntity;
import com.lanysec.entity.FlowParserEntity;
import com.lanysec.entity.FlowStaticEntity;
import com.lanysec.utils.ConversionUtil;
import com.lanysec.utils.DbConnectUtil;
import com.lanysec.utils.StringUtil;
import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.functions.timestamps.BoundedOutOfOrdernessTimestampExtractor;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer010;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.java.StreamTableEnvironment;
import org.apache.flink.table.functions.ScalarFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;

/**
 * @author daijb
 * @date 2021/3/8 16:27
 * ??????????????????????????????
 * ????????????????????????:
 * --mysql.servers 192.168.3.101
 * --bootstrap.servers 192.168.3.101:6667
 * --topic csp_flow //??????
 * --check.topic csp_event // ?????????????????????topic
 * --group.id test
 * --interval 1m
 */
public class AssetSessionVisitFlow implements AssetSessionVisitConstants {

    private static final Logger logger = LoggerFactory.getLogger(AssetSessionVisitFlow.class);

    public static void main(String[] args) {
        AssetSessionVisitFlow assetSessionVisitFlow = new AssetSessionVisitFlow();
        // ????????????
        assetSessionVisitFlow.run(args);
    }

    public void run(String[] args) {
        logger.info("flink streaming is starting....");
        StringBuilder text = new StringBuilder(128);
        for (String s : args) {
            text.append(s).append("\t");
        }
        logger.info("all params : " + text.toString());
        Properties properties = JavaKafkaConfigurer.getKafkaProperties(args);
        System.setProperty("mysql.servers", properties.getProperty("mysql.servers"));
        // ??????????????????
        startFunc();

        // ????????????
        //TODO ????????????
        updateModelTaskStatus(ModelStatus.RUNNING);

        StreamExecutionEnvironment streamExecutionEnvironment = StreamExecutionEnvironment.getExecutionEnvironment();

        streamExecutionEnvironment.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);

        EnvironmentSettings fsSettings = EnvironmentSettings.newInstance().useOldPlanner().inStreamingMode().build();
        //?????? TableEnvironment
        StreamTableEnvironment streamTableEnvironment = StreamTableEnvironment.create(streamExecutionEnvironment, fsSettings);

        //??????kafka????????????
        logger.info("load kafka properties : " + properties.toString());
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getProperty("bootstrap.servers"));
        //???????????????????????????????????????????????????30s
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
        //??????poll???????????????
        //???????????????????????????????????????poll?????????????????????????????????poll???????????????????????????????????????????????????????????????
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 30);
        //????????????????????????????????????
        //?????????????????????????????????????????????????????????
        props.put(ConsumerConfig.GROUP_ID_CONFIG, properties.getProperty("group.id"));

        String intervalOriginal = ConversionUtil.str2Format(ConversionUtil.toString(properties.get("interval")));

        // ??????kafka source
        // ??????kafka????????????????????????
        DataStream<String> kafkaSource = streamExecutionEnvironment.addSource(new FlinkKafkaConsumer010<>(properties.getProperty("topic"), new SimpleStringSchema(), props));

        DataStream<String> kafkaSourceFilter = kafkaSource.filter((FilterFunction<String>) value -> {
            JSONObject line = (JSONObject) JSONValue.parse(value);
            if (!StringUtil.isEmpty(ConversionUtil.toString(line.get("SrcID")))) {
                return true;
            }
            if (StringUtil.isEmpty(ConversionUtil.toString(line.get("DstID")))) {
                return false;
            }
            return false;
        });

        // ???????????????????????????
        DataStream<String> matchAssetSourceStream = kafkaSourceFilter.map(new AssetMapSourceFunction())
                .filter((FilterFunction<String>) value -> !StringUtil.isEmpty(value));

        //??????????????????

        //????????????tag,?????????
        OutputTag<FlowEntity> outputTag = new OutputTag<>("side-output", TypeInformation.of(FlowEntity.class));
        //??????entity
        DataStream<FlowEntity> kafkaProcessStream = matchAssetSourceStream.process(new ParserKafkaProcessFunction());
        // ??????
        SingleOutputStreamOperator<FlowEntity> flowProcessSplitStream = kafkaProcessStream.process(new ProcessFunction<FlowEntity, FlowEntity>() {
            @Override
            public void processElement(FlowEntity value, Context ctx, Collector<FlowEntity> out) throws Exception {
                //?????????????????????????????????????????????????????????????????????mainDataStream??????????????????????????????????????????
                //???????????????????????????????????????????????????????????????mainDataStream?????????????????????
                //????????????
                out.collect(value);
                //?????????????????? ????????????
                ctx.output(outputTag, value);
            }
        });

        // TODO ??????????????? ?????????????????????group by
        //????????????
        SingleOutputStreamOperator<FlowEntity> inFlowProcessStream = flowProcessSplitStream.assignTimestampsAndWatermarks(new BrowseBoundedOutOfOrderTimestampExtractor(Time.seconds(5)));

        // ????????????
        SingleOutputStreamOperator<FlowEntity> outFlowProcessStream = flowProcessSplitStream.getSideOutput(outputTag).assignTimestampsAndWatermarks(new BrowseBoundedOutOfOrderTimestampExtractor(Time.seconds(5)));

        // ??????kafka?????????
        streamTableEnvironment.createTemporaryView("kafka_asset_in_flow", inFlowProcessStream, "srcId,srcIp,srcPort,areaId,l4p,inFlow,rowtime.rowtime");
        streamTableEnvironment.createTemporaryView("kafka_asset_out_flow", outFlowProcessStream, "srcId,srcIp,srcPort,areaId,l4p,outFlow,rowtime.rowtime");

        //4?????????UDF
        //??????????????????: ???Flink Window Start/End Timestamp???????????????????????????(???????????????????????????)
        streamTableEnvironment.registerFunction("UDFTimestampConverter", new UDFTimestampConverter());

        // ??????sql
        // ?????????????????????????????????(???????????????)
        String inFlowSql = "select srcIp,srcId,areaId,l4p as protocol,sum(inFlow) as flowSize,count(1) as totalCount," +
                "UDFTimestampConverter(TUMBLE_END(rowtime, INTERVAL " + intervalOriginal + " ),'YYYY-MM-dd','+08:00') as cntDate " +
                "from kafka_asset_in_flow " +
                "group by areaId,srcId,srcIp,l4p,TUMBLE(rowtime, INTERVAL " + intervalOriginal + ")";

        String outFlowSql = "select srcIp,srcId,areaId,l4p as protocol,sum(outFlow) as flowSize,count(1) as totalCount," +
                "UDFTimestampConverter(TUMBLE_END(rowtime, INTERVAL " + intervalOriginal + " ),'YYYY-MM-dd','+08:00') as cntDate " +
                "from kafka_asset_out_flow " +
                "group by areaId,srcId,srcIp,l4p,TUMBLE(rowtime, INTERVAL " + intervalOriginal + ")";

        // ????????????
        Table inFlowTable = streamTableEnvironment.sqlQuery(inFlowSql);
        Table outFlowTable = streamTableEnvironment.sqlQuery(outFlowSql);

        DataStream<FlowParserEntity> inFlowSinkEntityDataStream = streamTableEnvironment.toAppendStream(inFlowTable, FlowParserEntity.class);
        DataStream<FlowParserEntity> outFlowSinkEntityDataStream = streamTableEnvironment.toAppendStream(outFlowTable, FlowParserEntity.class);


        // ??????kafka?????????
        streamTableEnvironment.createTemporaryView("calculate_in_flow", inFlowSinkEntityDataStream, "srcId,srcIp,protocol,areaId,flowSize,totalCount,rowtime.rowtime");
        streamTableEnvironment.createTemporaryView("calculate_out_flow", outFlowSinkEntityDataStream, "srcId,srcIp,protocol,areaId,flowSize,totalCount,rowtime.rowtime");

        // ?????????????????????????????????
        String intervalTime2 = ConversionUtil.str2Format2(ConversionUtil.toString(properties.get("interval")));

        String inFlowCalculate = "select srcId,srcIp,areaId,protocol," +
                "UDFTimestampConverter(TUMBLE_END(rowtime, INTERVAL " + intervalTime2 + "),'YYYY-MM-dd','+08:00') as cDate," +
                "((sum(flowSize)/sum(totalCount))+1.96*STDDEV_POP(flowSize)/SQRT(sum(totalCount))) as maxFlowSize," +
                "((sum(flowSize)/sum(totalCount))-1.96*STDDEV_POP(flowSize)/SQRT(sum(totalCount))) as minFlowSize " +
                " from calculate_in_flow " +
                " group by srcId,srcIp,areaId,protocol,TUMBLE(rowtime, INTERVAL " + intervalTime2 + ")";

        String outFlowCalculate = "select srcId,srcIp,areaId,protocol," +
                "UDFTimestampConverter(TUMBLE_END(rowtime, INTERVAL " + intervalTime2 + "),'YYYY-MM-dd','+08:00') as cDate," +
                "((sum(flowSize)/sum(totalCount))+1.96*STDDEV_POP(flowSize)/SQRT(sum(totalCount))) as maxFlowSize," +
                "((sum(flowSize)/sum(totalCount))-1.96*STDDEV_POP(flowSize)/SQRT(sum(totalCount))) as minFlowSize " +
                " from calculate_in_flow " +
                " group by srcId,srcIp,areaId,protocol,TUMBLE(rowtime, INTERVAL " + intervalTime2 + ")";

        Table inFlowCalculateTable = streamTableEnvironment.sqlQuery(inFlowCalculate);
        Table outFlowCalculateTable = streamTableEnvironment.sqlQuery(outFlowCalculate);

        DataStream<JSONObject> inFlowStaticStream = streamTableEnvironment.toAppendStream(inFlowCalculateTable, FlowStaticEntity.class)
                .map((MapFunction<FlowStaticEntity, JSONObject>) value -> {
                    JSONObject json = value.toJSONObject();
                    //0 ???????????? in ;1 ???????????? out'
                    json.put("flowType", 0);
                    return json;
                });

        DataStream<JSONObject> outFlowStaticStream = streamTableEnvironment.toAppendStream(outFlowCalculateTable, FlowStaticEntity.class)
                .map((MapFunction<FlowStaticEntity, JSONObject>) value -> {
                    JSONObject json = value.toJSONObject();
                    //0 ???????????? in ;1 ???????????? out'
                    json.put("flowType", 1);
                    return json;
                });

        // sink
        inFlowStaticStream.addSink(new SessionFlowSink());
        outFlowStaticStream.addSink(new SessionFlowSink());


        try {
            streamExecutionEnvironment.execute("kafka message streaming start ....");
        } catch (Exception e) {
            logger.error("flink streaming execute failed", e);
            // ????????????
            updateModelTaskStatus(ModelStatus.STOP);
        }
    }

    /**
     * ??????????????????
     *
     * @param modelStatus ????????????
     */
    private void updateModelTaskStatus(ModelStatus modelStatus) {
        Object modelId = ModelParamsConfigurer.getModelingParams().get(MODEL_ID);
        String updateSql = "UPDATE `modeling_params` SET `model_task_status`=?, `modify_time`=? " +
                " WHERE (`id`='" + modelId + "');";
        DbConnectUtil.execUpdateTask(updateSql, modelStatus.toString().toLowerCase(), LocalDateTime.now().toString());
        logger.info("[AssetSessionVisitFlow] update model task status : " + modelStatus.name());
    }

    private void startFunc() {
        logger.info("starting build model params.....");
        new Timer("timer-model").schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    ModelParamsConfigurer.reloadModelingParams();
                    ModelParamsConfigurer.reloadBuildModelAssetId();
                    logger.info("reload model params configurer.");
                } catch (Throwable throwable) {
                    logger.error("timer schedule at fixed rate failed ", throwable);
                }
            }
        }, 1000 * 10, 1000 * 60 * 5);

        new Timer("timer-model").schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    ModelParamsConfigurer.queryLastBuildModelResult();
                    logger.info("reload build model result.");
                } catch (Throwable throwable) {
                    logger.error("timer schedule at fixed rate failed ", throwable);
                }
            }
        }, 1000 * 60 * 5, 1000 * 60 * 30);
    }

    /**
     * ??????kafka flow ????????????
     */
    private static class ParserKafkaProcessFunction extends ProcessFunction<String, FlowEntity> {

        @Override
        public void processElement(String value, Context ctx, Collector<FlowEntity> out) throws Exception {
            FlowEntity flowEntity = JSON.parseObject(value, FlowEntity.class);
            //???????????????
            out.collect(flowEntity);
        }
    }

    /**
     * ?????????UDF
     */
    public static class UDFTimestampConverter extends ScalarFunction {

        /**
         * ???????????????????????????
         *
         * @param timestamp Flink Timestamp ????????????
         * @param format    ????????????,???"YYYY-MM-dd HH:mm:ss"
         * @return ?????????????????????
         */
        public String eval(Timestamp timestamp, String format) {

            LocalDateTime noZoneDateTime = timestamp.toLocalDateTime();
            ZonedDateTime utcZoneDateTime = ZonedDateTime.of(noZoneDateTime, ZoneId.of("UTC"));

            ZonedDateTime targetZoneDateTime = utcZoneDateTime.withZoneSameInstant(ZoneId.of("+08:00"));

            return targetZoneDateTime.format(DateTimeFormatter.ofPattern(format));
        }

        /**
         * ???????????????????????????
         *
         * @param timestamp  Flink Timestamp ????????????
         * @param format     ????????????,???"YYYY-MM-dd HH:mm:ss"
         * @param zoneOffset ?????????????????????
         * @return ?????????????????????
         */
        public String eval(Timestamp timestamp, String format, String zoneOffset) {

            LocalDateTime noZoneDateTime = timestamp.toLocalDateTime();
            ZonedDateTime utcZoneDateTime = ZonedDateTime.of(noZoneDateTime, ZoneId.of("UTC"));

            ZonedDateTime targetZoneDateTime = utcZoneDateTime.withZoneSameInstant(ZoneId.of(zoneOffset));

            return targetZoneDateTime.format(DateTimeFormatter.ofPattern(format));
        }
    }

    /**
     * ???????????????????????????
     */
    public static class BrowseBoundedOutOfOrderTimestampExtractor extends BoundedOutOfOrdernessTimestampExtractor<FlowEntity> {

        BrowseBoundedOutOfOrderTimestampExtractor(org.apache.flink.streaming.api.windowing.time.Time maxOutOfOrder) {
            super(maxOutOfOrder);
        }

        @Override
        public long extractTimestamp(FlowEntity element) {
            return element.getrTime();
        }
    }

}

