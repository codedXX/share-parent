package com.share.device.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.share.device.domain.Cabinet;
import com.share.device.domain.Station;
import com.share.device.domain.StationLocation;
import com.share.device.mapper.StationMapper;
import com.share.device.repository.StationLocationRepository;
import com.share.device.service.ICabinetService;
import com.share.device.service.IRegionService;
import com.share.device.service.IStationService;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class StationServiceImpl extends ServiceImpl<StationMapper, Station> implements IStationService {
    @Autowired
    private StationMapper stationMapper;
    @Autowired
    private ICabinetService cabinetService;
    @Autowired
    private IRegionService regionService;
    @Autowired
    private StationLocationRepository stationLocationRepository;

    @Override
    public List<Station> selectStationList(Station station) {
        // 1.根据查询条件查询站点列表
        List<Station> list = stationMapper.selectStationList(station);
        // 2.从站点列表中提取所有机柜ID
        List<Long> cabinetIdList = list.stream().map(station1 -> station1.getCabinetId()).collect(Collectors.toList());
        Map<Long, String> cabinetIdToCabinetNoMap = new HashMap<>();
        // 3.机柜ID列表不为空时，批量查询机柜信息，构建 机柜ID -> 机柜编号 的映射
        if (!CollectionUtils.isEmpty(cabinetIdList)) {
            List<Cabinet> cabinetList = cabinetService.list(new LambdaQueryWrapper<Cabinet>()
                    .in(Cabinet::getId, cabinetIdList));

            cabinetIdToCabinetNoMap = cabinetList.stream().collect(Collectors.toMap(Cabinet::getId, Cabinet::getCabinetNo));
        }
        // 4.遍历站点列表，根据机柜ID回填机柜编号
        for (Station item : list) {
            item.setCabinetNo(cabinetIdToCabinetNoMap.get(item.getCabinetId()));

        }
        // 5.返回带机柜编号的站点列表
        return list;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public int saveStation(Station station) {
        String provinceName = regionService.getNameByCode(station.getProvinceCode());
        String cityName = regionService.getNameByCode(station.getCityCode());
        String districtName = regionService.getNameByCode(station.getDistrictCode());
        station.setFullAddress(provinceName + cityName + districtName + station.getAddress());
        this.save(station);

        //同步站点位置信息到MongoDB
        StationLocation stationLocation = new StationLocation();
        //ObjectId 是 MongoDB BSON 库中的类（org.bson.types.ObjectId），ObjectId.get() 是一个静态工厂方法，用于生成一个新的唯一 ID（类似 UUID 的全局唯一标识符）。
        stationLocation.setId(ObjectId.get().toString());
        stationLocation.setStationId(station.getId());
        stationLocation.setLocation(new GeoJsonPoint(station.getLongitude().doubleValue(), station.getLatitude().doubleValue()));
        stationLocation.setCreateTime(new Date());
        stationLocationRepository.save(stationLocation);
        return 1;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public int updateStation(Station station) {
        String provinceName = regionService.getNameByCode(station.getProvinceCode());
        String cityName = regionService.getNameByCode(station.getCityCode());
        String districtName = regionService.getNameByCode(station.getDistrictCode());
        station.setFullAddress(provinceName + cityName + districtName + station.getAddress());
        this.updateById(station);

        //同步站点位置信息到MongoDB
        StationLocation stationLocation = stationLocationRepository.getByStationId(station.getId());
        stationLocation.setLocation(new GeoJsonPoint(station.getLongitude().doubleValue(), station.getLatitude().doubleValue()));
        stationLocationRepository.save(stationLocation);
        return 1;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean removeByIds(Collection<?> list) {
        for (Object id : list) {
            stationLocationRepository.deleteByStationId(Long.parseLong(id.toString()));
        }
        return super.removeByIds(list);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public int setData(Station station) {
        this.updateById(station);

        //更正柜机使用状态
        Cabinet cabinet = cabinetService.getById(station.getCabinetId());
        cabinet.setStatus("1");
        cabinetService.updateById(cabinet);
        return 1;
    }

}