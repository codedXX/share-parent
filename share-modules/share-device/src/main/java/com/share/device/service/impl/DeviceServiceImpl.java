package com.share.device.service.impl;


import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.share.common.core.constant.SecurityConstants;
import com.share.common.core.context.SecurityContextHolder;
import com.share.common.core.exception.ServiceException;
import com.share.common.security.utils.SecurityUtils;
import com.share.device.domain.*;
import com.share.device.service.ICabinetService;
import com.share.device.service.IDeviceService;
import com.share.device.service.IMapService;
import com.share.device.service.IStationService;
import com.share.rules.api.RemoteFeeRuleService;
import com.share.rules.domain.FeeRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DeviceServiceImpl implements IDeviceService {
    @Autowired
    private IStationService stationService;

    @Autowired
    private ICabinetService cabinetService;
    @Autowired
    private IMapService mapService;
    @Autowired
    private RemoteFeeRuleService remoteFeeRuleService;


    @Autowired
    private MongoTemplate mongoTemplate;

    @Override
    public List<StationVo> nearbyStation(String latitude, String longitude, Integer radius) {
        /**
         * 第一步：用 MongoDB 找出地理上"附近的站点"
         */
        //坐标，确定中心点
        // GeoJsonPoint(double x, double y) x 表示经度，y 表示纬度。

        GeoJsonPoint geoJsonPoint = new GeoJsonPoint(Double.parseDouble(longitude), Double.parseDouble(latitude));
        //画圈的半径,50km范围
        Distance d = new Distance(radius, Metrics.KILOMETERS);
        //画了一个圆圈
        Circle circle = new Circle(geoJsonPoint, d);
        //以用户位置为圆心，查找半径范围内的站点位置
        Query query = Query.query(Criteria.where("location").withinSphere(circle));
        //第二个参数作用：告诉 MongoTemplate：把查询结果自动转换成哪个 Java 类型的对象。
        List<StationLocation> stationLocationList = this.mongoTemplate.find(query, StationLocation.class);
        if (CollectionUtils.isEmpty(stationLocationList)) return null;

        /**
         * 第二步：用 MySQL 查这些站点的详情和柜机库存
         */
        //组装数据
        List<Long> stationIdList =stationLocationList.stream().map(StationLocation::getStationId).collect(Collectors.toList());

        //获取站点列表
        List<Station> stationList = stationService.list(new LambdaQueryWrapper<Station>().in(Station::getId, stationIdList).isNotNull(Station::getCabinetId));

        //获取柜机id列表
        List<Long> cabinetIdList = stationList.stream().map(Station::getCabinetId).collect(Collectors.toList());
        //获取柜机id与柜机信息Map
        //第二个参数：参数：遍历时拿到的每个 Cabinet 对象 ; 返回值：把这个对象本身作为 value
        Map<Long, Cabinet> cabinetIdToCabinetMap = cabinetService.listByIds(cabinetIdList).stream().collect(Collectors.toMap(Cabinet::getId, Cabinet -> Cabinet));

        List<StationVo> stationVoList = new ArrayList<>();
        /**
         * 第三步：根据柜机库存，给每个站点打上"可借/可还"标记
         */
        stationList.forEach(item -> {
            StationVo stationVo = new StationVo();
            BeanUtils.copyProperties(item, stationVo);

            // 获取柜机信息
            Cabinet cabinet = cabinetIdToCabinetMap.get(item.getCabinetId());
            //可用充电宝数量大于0，可借用
            if (cabinet.getAvailableNum() > 0) {
                stationVo.setIsUsable("1");
            } else {
                stationVo.setIsUsable("0");
            }
            // 获取空闲插槽数量大于0，可归还
            if (cabinet.getFreeSlots() > 0) {
                stationVo.setIsReturn("1");
            } else {
                stationVo.setIsReturn("0");
            }

            stationVoList.add(stationVo);
        });

        /**
         *  业务含义：遍历每个站点，根据它柜机的库存情况打两个标记：
         * - isUsable = "1"：柜机里还有可用的充电宝，用户可以借
         * - isReturn = "1"：柜机还有空闲插槽，用户可以还（插回去）
         */
        return stationVoList;
    }


    @Override
    public StationVo getStation(Long id, String latitude, String longitude) {
        Station station = stationService.getById(id);
        StationVo stationVo = new StationVo();
        BeanUtils.copyProperties(station, stationVo);
        // 计算距离
        Double distance = mapService.calculateDistance(longitude, latitude, station.getLongitude().toString(), station.getLatitude().toString());
        stationVo.setDistance(distance);

        // 获取柜机信息
        Cabinet cabinet = cabinetService.getById(station.getCabinetId());
        //可用充电宝数量大于0，可借用
        if(cabinet.getAvailableNum() > 0) {
            stationVo.setIsUsable("1");
        } else {
            stationVo.setIsUsable("0");
        }
        // 获取空闲插槽数量大于0，可归还
        if (cabinet.getFreeSlots() > 0) {
            stationVo.setIsReturn("1");
        } else {
            stationVo.setIsReturn("0");
        }

        // 获取费用规则
        FeeRule feeRule = remoteFeeRuleService.getFeeRule(station.getFeeRuleId()).getData();
        stationVo.setFeeRule(feeRule.getDescription());
        return stationVo;
    }

}
