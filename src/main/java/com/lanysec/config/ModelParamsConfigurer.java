package com.lanysec.config;

import com.lanysec.services.AssetBehaviorConstants;
import com.lanysec.utils.ConversionUtil;
import com.lanysec.utils.DbConnectUtil;
import com.lanysec.utils.SystemUtil;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.io.jdbc.JDBCInputFormat;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.json.simple.JSONArray;
import org.json.simple.JSONValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author daijb
 * @date 2021/3/8 16:24
 */
public class ModelParamsConfigurer implements AssetBehaviorConstants {

    private static final Logger logger = LoggerFactory.getLogger(ModelParamsConfigurer.class);

    //private static final JDBCInputFormat jdbcInputFormat = getModelingParamsStream0();

    private static Map<String, Object> modelingParams;

    private static volatile boolean isFirst = true;

    /**
     * 返回建模参数
     *
     * @return 建模参数k-v
     */
    public static Map<String, Object> getModelingParams() {
        if (modelingParams == null) {
            modelingParams = reloadModelingParams();
        }
        return modelingParams;
    }

    /**
     * 从数据库获取建模参数
     */
    public static Map<String, Object> reloadModelingParams() {
        Connection connection = DbConnectUtil.getConnection();
        Map<String, Object> result = new HashMap<>(15 * 3 / 4);
        try {
            //TODO model_type | model_child_type实际需要修改为 对应值 ,这里只是测试
            String sql = "select * from modeling_params" +
                    " where model_type=1 and model_child_type =1" +
                    " and model_switch = 1 and model_switch_2 =1" +
                    " and modify_time < DATE_SUB( NOW(), INTERVAL 10 MINUTE );";
            ResultSet resultSet = connection.createStatement().executeQuery(sql);
            while (resultSet.next()) {
                result.put(MODEL_ID, resultSet.getString("id"));
                result.put(MODEL_TYPE, resultSet.getString("model_type"));
                result.put(MODEL_CHILD_TYPE, resultSet.getString("model_child_type"));
                result.put(MODEL_RATE_TIME_UNIT, resultSet.getString("model_rate_timeunit"));
                result.put(MODEL_RATE_TIME_UNIT_NUM, resultSet.getString("model_rate_timeunit_num"));
                result.put(MODEL_RESULT_SPAN, resultSet.getString("model_result_span"));
                result.put(MODEL_RESULT_TEMPLATE, resultSet.getString("model_result_template"));
                result.put(MODEL_CONFIDENCE_INTERVAL, resultSet.getString("model_confidence_interval"));
                result.put(MODEL_HISTORY_DATA_SPAN, resultSet.getString("model_history_data_span"));
                result.put(MODEL_UPDATE, resultSet.getString("model_update"));
                result.put(MODEL_SWITCH, resultSet.getString("model_switch"));
                result.put(MODEL_SWITCH_2, resultSet.getString("model_switch_2"));
                result.put(MODEL_ATTRS, resultSet.getString("model_alt_params"));
                result.put(MODEL_TASK_STATUS, resultSet.getString("model_task_status"));
                result.put(MODEL_MODIFY_TIME, resultSet.getString("modify_time"));
            }
        } catch (Throwable throwable) {
            logger.error("Get modeling parameters from the database error ", throwable);
        }
        logger.info("Get modeling parameters from the database : " + result.toString());
        return result;
    }

    /**
     * 获取建模参数
     */
    private static JDBCInputFormat getModelingParamsStream0() {
        TypeInformation<?>[] fieldTypes = new TypeInformation<?>[]{
                BasicTypeInfo.STRING_TYPE_INFO,
                BasicTypeInfo.INT_TYPE_INFO,
                BasicTypeInfo.INT_TYPE_INFO,
                BasicTypeInfo.STRING_TYPE_INFO,
                BasicTypeInfo.INT_TYPE_INFO,
                BasicTypeInfo.INT_TYPE_INFO,
                BasicTypeInfo.STRING_TYPE_INFO,
                BasicTypeInfo.FLOAT_TYPE_INFO,
                BasicTypeInfo.INT_TYPE_INFO,
                BasicTypeInfo.INT_TYPE_INFO,
                BasicTypeInfo.INT_TYPE_INFO,
                BasicTypeInfo.INT_TYPE_INFO,
                BasicTypeInfo.STRING_TYPE_INFO,
                BasicTypeInfo.STRING_TYPE_INFO,
                BasicTypeInfo.DATE_TYPE_INFO,
        };
        RowTypeInfo rowTypeInfo = new RowTypeInfo(fieldTypes);
        String addr = SystemUtil.getHostIp();
        return JDBCInputFormat.buildJDBCInputFormat()
                .setDrivername("com.mysql.jdbc.Driver")
                .setDBUrl("jdbc:mysql://" + addr + ":3306/csp?useEncoding=true&characterEncoding=utf-8&serverTimezone=UTC")
                .setUsername(SystemUtil.getMysqlUser())
                .setPassword(SystemUtil.getMysqlPassword())
                .setQuery("select * from modeling_params where model_type='1' and model_child_type='3';")
                .setRowTypeInfo(rowTypeInfo)
                .finish();
    }

    private static volatile List<Map<String, JSONArray>> lastBuildModelResult;

    public static List<Map<String, JSONArray>> getLastBuildModelResult() {
        if (lastBuildModelResult == null) {
            queryLastBuildModelResult();
        }
        return lastBuildModelResult;
    }

    /**
     * 查询上次建模结果
     */
    public static void queryLastBuildModelResult() {

        List<Map<String, JSONArray>> result = new ArrayList<>();
        String modelId = ConversionUtil.toString(ModelParamsConfigurer.getModelingParams().get("modelId"));
        String querySql = "select src_id,flow from model_result_asset_session_flow " +
                "where modeling_params_id='" + modelId + "';";
        try {
            ResultSet resultSet = DbConnectUtil.getConnection().createStatement().executeQuery(querySql);
            while (resultSet.next()) {
                Map<String, JSONArray> map = new HashMap<>();
                String srcId = resultSet.getString("src_id");
                String segmentStr = resultSet.getString("flow");
                JSONArray segmentArr = (JSONArray) JSONValue.parse(segmentStr);
                map.put(srcId, segmentArr);
                result.add(map);
            }
        } catch (SQLException sqlException) {
            logger.error("query build `session_flow` model result failed", sqlException);
        }
        lastBuildModelResult = result;
    }
}
