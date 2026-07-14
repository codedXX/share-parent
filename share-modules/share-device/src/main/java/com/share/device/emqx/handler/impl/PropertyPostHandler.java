package com.share.device.emqx.handler.impl;

import com.alibaba.fastjson2.JSONObject;
import com.share.device.emqx.annotation.GuiguEmqx;
import com.share.device.emqx.constant.EmqxConstants;
import com.share.device.emqx.handler.MassageHandler;
import com.share.device.service.ICabinetService;
import com.share.device.service.ICabinetSlotService;
import com.share.device.service.IPowerBankService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@GuiguEmqx(topic = EmqxConstants.TOPIC_PROPERTY_POST)
public class PropertyPostHandler implements MassageHandler {

    @Autowired
    private ICabinetService cabinetService;

    @Autowired
    private IPowerBankService powerBankService;

    @Autowired
    private ICabinetSlotService cabinetSlotService;

    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 处理消息:
     * @param message
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void handleMessage(JSONObject message) {


    }
}

