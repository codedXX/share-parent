package com.share.device.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.share.device.domain.Cabinet;

import java.util.List;
import java.util.Map;

public interface ICabinetService extends IService<Cabinet>
{

    public List<Cabinet> selectCabinetList(Cabinet cabinet);

    int saveCabinet(Cabinet cabinet);

    int updateCabinet(Cabinet cabinet);

    int removeCabinet(List<Long> idList);

    List<Cabinet> searchNoUseList(String keyword);

    Map<String, Object> getAllInfo(Long id);
}