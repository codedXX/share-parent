package com.share.device.service.impl;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.share.device.domain.Cabinet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.share.device.mapper.CabinetSlotMapper;
import com.share.device.domain.CabinetSlot;
import com.share.device.service.ICabinetSlotService;

/**
 * 柜机插槽Service业务层处理
 *
 * @author atguigu
 * @date 2024-10-22
 */
@Service
public class CabinetSlotServiceImpl extends ServiceImpl<CabinetSlotMapper, CabinetSlot> implements ICabinetSlotService
{
    @Autowired
    private CabinetSlotMapper cabinetSlotMapper;

    @Override
    public CabinetSlot getBtSlotNo(Long cabinetId, String slotNo) {
        return cabinetSlotMapper.selectOne(new LambdaQueryWrapper<CabinetSlot>().eq(CabinetSlot::getCabinetId, cabinetId).eq(CabinetSlot::getSlotNo, slotNo));
    }
}
