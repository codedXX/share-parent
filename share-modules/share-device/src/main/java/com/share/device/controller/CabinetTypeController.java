package com.share.device.controller;

import com.share.common.core.web.controller.BaseController;
import com.share.common.core.web.domain.AjaxResult;
import com.share.common.core.web.page.TableDataInfo;
import com.share.common.log.annotation.Log;
import com.share.common.log.enums.BusinessType;
import com.share.common.security.annotation.RequiresPermissions;
import com.share.device.domain.CabinetType;
import com.share.device.service.ICabinetTypeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

@Tag(name = "柜机类型接口管理")
@RestController
@RequestMapping("/cabinetType")
public class CabinetTypeController extends BaseController {

    @Autowired
    private ICabinetTypeService cabinetTypeService;

    @Operation(summary = "查询全部柜机类型列表")
    @GetMapping("/getCabinetTypeList")
    public AjaxResult getCabinetTypeList()
    {
        return success(cabinetTypeService.list());
    }

    //删除
    // 根据id删除某个数据，或者根据多个id删除多条记录
    // [1,2,3]
    @Operation(summary = "删除")
    @DeleteMapping("/{ids}")
    public AjaxResult delete(@PathVariable Long[] ids) {
        boolean is_Success = cabinetTypeService.removeBatchByIds(Arrays.asList(ids));
        AjaxResult ajaxResult = toAjax(is_Success);
        return ajaxResult;
    }

    //修改
    @Operation(summary = "修改")
    @PutMapping
    public AjaxResult update(@RequestBody CabinetType cabinetType) {
        boolean is_Success = cabinetTypeService.updateById(cabinetType);
        AjaxResult ajaxResult = toAjax(is_Success);
        return ajaxResult;
    }

    //添加
    @Operation(summary = "添加")
    @RequiresPermissions("device:cabinetType:add")
    @PostMapping
    public AjaxResult add(@RequestBody CabinetType cabinetType) {
        boolean is_Success = cabinetTypeService.save(cabinetType);
        AjaxResult ajaxResult = toAjax(is_Success);

        return ajaxResult;
    }

    //根据id查询详情
    @Operation(summary = "根据id查询详情")
    @GetMapping("{id}")
    public AjaxResult getCabinetType(@PathVariable Long id) {
        CabinetType cabinetType = cabinetTypeService.getById(id);
        AjaxResult ajaxResult = success(cabinetType);
        return ajaxResult;
    }

    //分页查询
    @Operation(summary = "柜机类型分页查询")
    @Log(title = "柜机类型查询",businessType= BusinessType.OTHER)
    @GetMapping("/list")
    public TableDataInfo list(CabinetType cabinetType) {
        //封装分页参数数据
        startPage();
        //调用service查询数据库
        List<CabinetType> list = cabinetTypeService.selectCabinetTypeList(cabinetType);
        TableDataInfo dataTable = getDataTable(list);
        return dataTable;
    }


}
