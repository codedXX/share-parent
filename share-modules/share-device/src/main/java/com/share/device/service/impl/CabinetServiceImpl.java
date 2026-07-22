package com.share.device.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.share.common.core.exception.ServiceException;
import com.share.common.security.utils.SecurityUtils;
import com.share.device.domain.Cabinet;
import com.share.device.domain.CabinetSlot;
import com.share.device.domain.CabinetType;
import com.share.device.domain.PowerBank;
import com.share.device.mapper.CabinetMapper;
import com.share.device.mapper.CabinetSlotMapper;
import com.share.device.mapper.CabinetTypeMapper;
import com.share.device.service.ICabinetService;
import com.share.device.service.IPowerBankService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class CabinetServiceImpl extends ServiceImpl<CabinetMapper, Cabinet> implements ICabinetService
{
    @Autowired
    private CabinetMapper cabinetMapper;
    @Autowired
    private CabinetTypeMapper cabinetTypeMapper;
    @Autowired
    private CabinetSlotMapper cabinetSlotMapper;
    @Autowired
    private IPowerBankService powerBankService;

    @Override
    public List<Cabinet> selectCabinetList(Cabinet cabinet)
    {
        return cabinetMapper.selectCabinetList(cabinet);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public int saveCabinet(Cabinet cabinet) {
        LambdaQueryWrapper<Cabinet> coutWrapper = new LambdaQueryWrapper<Cabinet>()
                .eq(Cabinet::getCabinetNo,cabinet.getCabinetNo());
        long count = count(coutWrapper);

        if (count > 0) {
            throw new ServiceException("该柜机编号已存在");
        }
        // 根据柜机类型id查询柜机类型
        CabinetType cabinetType = cabinetTypeMapper.selectById(cabinet.getCabinetTypeId());

        // 设置总插槽数量和可用插槽数量
        cabinet.setTotalSlots(cabinetType.getTotalSlots());
        cabinet.setFreeSlots(cabinetType.getTotalSlots());
        cabinet.setUsedSlots(0);
        cabinet.setAvailableNum(0);
        cabinet.setCreateTime(new Date());
        cabinet.setCreateBy(SecurityUtils.getUsername());
        this.save(cabinet);

        int size = cabinetType.getTotalSlots();
        for(int i=0; i<size; i++) {
            CabinetSlot cabinetSlot = new CabinetSlot();
            cabinetSlot.setCabinetId(cabinet.getId());
            cabinetSlot.setSlotNo(i+1+"");
            cabinetSlot.setCreateTime(new Date());
            cabinet.setCreateBy(SecurityUtils.getUsername());
            cabinetSlotMapper.insert(cabinetSlot);
        }
        return 1;
    }
    @Transactional(rollbackFor = Exception.class)
    @Override
    public int updateCabinet(Cabinet cabinet) {
        //获取旧的数据
//        LambdaQueryWrapper<Cabinet> eq = new LambdaQueryWrapper<Cabinet>()
//                .eq(Cabinet::);
        Cabinet oldCabinet = this.getById(cabinet.getId());
        if (oldCabinet != null && !"0".equals(oldCabinet.getStatus())) {
            throw new ServiceException("该柜机已投放，无法修改");
        }
        //判断柜机编号是否改变
        if (!oldCabinet.getCabinetNo().equals(cabinet.getCabinetNo())) {
            LambdaQueryWrapper<Cabinet> countWrapper = new LambdaQueryWrapper<Cabinet>()
                    .eq(Cabinet::getCabinetNo,cabinet.getCabinetNo());
            long count = count(countWrapper);
            if (count > 0) {
                throw new ServiceException("该柜机编号已存在");
            }

        }

        //判断是否修改了柜机类型
        /**
         * longValue()是什么意思？
         * 1.把包装类 Long 转成基本类型 long。
         * 2.两个 Long 对象用 != 比的是引用地址，不是数值。longValue() 拿到原始 long 值再比较，确保比的是数值。
         */
        if (oldCabinet.getCabinetTypeId().longValue() != cabinet.getCabinetTypeId()) {
            // 根据柜机类型id查询柜机类型
            CabinetType cabinetType = cabinetTypeMapper.selectById(cabinet.getCabinetTypeId());

            // 设置总插槽数量和可用插槽数量
            cabinet.setTotalSlots(cabinetType.getTotalSlots());
            cabinet.setTotalSlots(cabinetType.getTotalSlots());
            cabinet.setFreeSlots(cabinetType.getTotalSlots());
            cabinet.setUsedSlots(0);
            cabinet.setAvailableNum(0);
            cabinet.setUpdateTime(new Date());
            cabinet.setUpdateBy(SecurityUtils.getUsername());
            this.updateById(cabinet);

            // 删除所有插槽
            cabinetSlotMapper.delete(new LambdaQueryWrapper<CabinetSlot>().eq(CabinetSlot::getCabinetId, cabinet.getId()));
            int size = cabinetType.getTotalSlots();
            for(int i=0; i<size; i++) {
                CabinetSlot cabinetSlot = new CabinetSlot();
                cabinetSlot.setCabinetId(cabinet.getId());
                cabinetSlot.setSlotNo(i+1+"");
                cabinetSlot.setCreateTime(new Date());
                cabinet.setCreateBy(SecurityUtils.getUsername());
                cabinetSlotMapper.insert(cabinetSlot);
            }
        }else{
            cabinet.setUpdateTime(new Date());
            cabinet.setUpdateBy(SecurityUtils.getUsername());
            this.updateById(cabinet);
        }
        return 1;
    }

    @Override
    public int removeCabinet(List<Long> idList) {
        this.removeBatchByIds(idList);
        cabinetSlotMapper.delete(new LambdaQueryWrapper<CabinetSlot>().in(CabinetSlot::getCabinetId, idList));
        return 1;
    }

    @Override
    public List<Cabinet> searchNoUseList(String keyword) {
        LambdaQueryWrapper<Cabinet> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.like(Cabinet::getCabinetNo, keyword)
                .eq(Cabinet::getStatus, "0");
        return cabinetMapper.selectList(queryWrapper);
    }

    @Override
    public Map<String, Object> getAllInfo(Long id) {
        // 查询柜机信息
        Cabinet cabinet = this.getById(id);

        // 查询插槽信息
        List<CabinetSlot> cabinetSlotList = cabinetSlotMapper.selectList(new LambdaQueryWrapper<CabinetSlot>().eq(CabinetSlot::getCabinetId, cabinet.getId()));
        // 获取可用充电宝id列表
        List<Long> powerBankIdList = cabinetSlotList.stream().filter(item -> null != item.getPowerBankId()).map(CabinetSlot::getPowerBankId).collect(Collectors.toList());
        if(!CollectionUtils.isEmpty(powerBankIdList)) {
            List<PowerBank> powerBankList = powerBankService.listByIds(powerBankIdList);
            Map<Long,PowerBank> powerBankIdToPowerBankMap = powerBankList.stream().collect(Collectors.toMap(PowerBank::getId, PowerBank -> PowerBank));
            cabinetSlotList.forEach(item -> item.setPowerBank(powerBankIdToPowerBankMap.get(item.getPowerBankId())));
        }

        /**
         * 返回一个 Map，包含：
         * - cabinet：柜机本体
         * - cabinetSlotList：插槽列表（每个插槽里已经带上了它的充电宝信息）
         */
        Map<String, Object> result = Map.of("cabinet", cabinet, "cabinetSlotList", cabinetSlotList);
        return result;
    }
}