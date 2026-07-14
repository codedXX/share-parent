package com.share.device.service;

public interface IMapService {

    //计算距离
    // 四个参数：开始经纬度， 目标经纬度
    Double calculateDistance(String startLongitude,String startLatitude,
                             String endLongitude,String endLatitude);
}
