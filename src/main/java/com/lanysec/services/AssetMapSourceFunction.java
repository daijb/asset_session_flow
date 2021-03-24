package com.lanysec.services;

import com.lanysec.utils.ConversionUtil;
import com.lanysec.utils.DbConnectUtil;
import com.lanysec.utils.StringUtil;
import com.lanysec.utils.SystemUtil;
import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.configuration.Configuration;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author daijb
 * @date 2021/3/8 16:40
 */
public class AssetMapSourceFunction extends RichMapFunction<String, String> {

    private final Set<String> allAssetIds = new HashSet<>();

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        Connection connection = DbConnectUtil.getConnection();

        String sql = "SELECT entity_id, entity_name, asset_ip, area_id " +
                "FROM asset a, modeling_params m " +
                "WHERE m.model_alt_params -> '$.model_entity_group' LIKE CONCAT('%', a.entity_groups,'%') " +
                "and m.model_type=1 and model_child_type=1 " +
                "and model_switch=1 and model_switch_2=1;";
        ResultSet resultSet = connection.prepareStatement(sql).executeQuery();
        while (resultSet.next()) {
            String entityId = ConversionUtil.toString(resultSet.getString("entity_id"));
            allAssetIds.add(entityId);
        }
    }

    @Override
    public String map(String line) throws Exception {
        //{"L7P":"tls","InPackets":20,"ClusterID":"EWN7S4UJ","L3P":"IP","OutFlow":1350,"OutPackets":10,"OutFlags":0,"InFlow":1950,"rHost":"192.168.9.58","SrcID":"ast_458b6b75b0610d081dc83c7c6a34498a","ID":"fle_TTJoPs5YQoTQwZqGbkfvmG","sTime":1615090860000,"SrcCountry":"中国北京","rType":"1","SrcPort":17339,"DstLocName":"杭州","eTime":1615090981000,"AreaID":19778692,"L4P":"TCP","DstCountry":"中国","InFlags":0,"MetaID":"evm_flow","DstPort":443,"SrcIP":"192.168.7.249","ESMetaID":"esm_flow","SID":"1296f58e4f3ab1c3ced4bce072532608","FlowID":"1296f58e4f3ab1c3ced4bce072532608","rTime":1615090981139,"@timestamp":1615090995592,"DstID":"","DstIP":"47.111.111.35","DstMAC":"bc:3f:8f:63:6c:80","PcapID":"","TrafficSource":"eth1","SrcMAC":"4c:cc:6a:57:95:8a","Key":"","SrcLocName":"未知"}
        JSONObject json = (JSONObject) JSONValue.parse(line);
        String srcId = ConversionUtil.toString(json.get("SrcID"));
        if (allAssetIds.contains(srcId)) {
            return json.toJSONString();
        }
        return "";
    }
}

