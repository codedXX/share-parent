package com.share.device.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.share.common.core.exception.ServiceException;
import com.share.device.service.IMapService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class MapServiceImpl implements IMapService {

    @Autowired
    private RestTemplate restTemplate;

    private String key= "PYOBZ-Y6ZRZ-HMZXP-ZTMES-TNAQ7-WZFYS";

    //计算距离
    // 四个参数：开始经纬度， 目标经纬度
    @Override
    public Double calculateDistance(String startLongitude, String startLatitude,
                                    String endLongitude, String endLatitude) {
//        String url = "https://apis.map.qq.com/ws/direction/v1/walking/?from={from}&to={to}&key={key}";
//
//        Map<String, String> map = new HashMap<>();
//        map.put("from", startLatitude + "," + startLongitude);
//        map.put("to", endLatitude + "," + endLongitude);
//        map.put("key", key);
//
//        JSONObject result = restTemplate.getForObject(url, JSONObject.class, map);
//        if(result.getIntValue("status") != 0) {
//            String message = result.getString("message");
//            System.out.println("*****==============="+message);
//            throw new ServiceException("地图服务调用失败");
//        }
//
//        //返回第一条最佳线路
//        JSONObject route = result.getJSONObject("result").getJSONArray("routes").getJSONObject(0);
//        // 单位：米
//        return route.getBigDecimal("distance").doubleValue();

        Random random = new Random();
        BigDecimal randomDouble = BigDecimal.valueOf(random.nextDouble(100));

// 保留两位小数，并进行四舍五入
        BigDecimal roundedValue = randomDouble.setScale(1, RoundingMode.HALF_UP);
        double roundedDoubleValue = roundedValue.doubleValue();
        return roundedDoubleValue;
    }
}
