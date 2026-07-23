package com.share.user.controller;

import com.share.common.core.context.SecurityContextHolder;
import com.share.common.core.domain.R;
import com.share.common.core.utils.poi.ExcelUtil;
import com.share.common.core.web.controller.BaseController;
import com.share.common.core.web.domain.AjaxResult;
import com.share.common.core.web.page.TableDataInfo;
import com.share.common.log.annotation.Log;
import com.share.common.log.enums.BusinessType;
import com.share.common.security.annotation.RequiresLogin;
import com.share.common.security.annotation.RequiresPermissions;
import com.share.user.domain.UpdateUserLogin;
import com.share.user.domain.UserInfo;
import com.share.user.domain.UserVo;
import com.share.user.service.IUserInfoService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/userInfo")
public class UserInfoApiController extends BaseController {

    @Autowired
    private IUserInfoService userInfoService;


    /**
     * 查询用户列表
     */
    @Operation(summary = "查询用户列表")
    @RequiresPermissions("user:userInfo:list")
    @GetMapping("/list")
    public TableDataInfo list(UserInfo userInfo)
    {
        startPage();
        List<UserInfo> list = userInfoService.selectUserInfoList(userInfo);
        return getDataTable(list);
    }

    /**
     * 导出用户列表
     */
    @Operation(summary = "导出用户列表")
    @RequiresPermissions("user:userInfo:export")
    @Log(title = "用户", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, UserInfo userInfo)
    {
        List<UserInfo> list = userInfoService.selectUserInfoList(userInfo);
        ExcelUtil<UserInfo> util = new ExcelUtil<UserInfo>(UserInfo.class);
        util.exportExcel(response, list, "用户数据");
    }

    /**
     * 获取用户详细信息
     */
    @Operation(summary = "获取用户详细信息")
    @RequiresPermissions("user:userInfo:query")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(userInfoService.getById(id));
    }

    /**
     * 微信登录
     */
    @GetMapping("/wxLogin/{code}")
    public R<UserInfo> wxLogin(@PathVariable("code") String code)
    {
        return R.ok(userInfoService.wxLogin(code));
    }

    /**
     * 新增用户
     */
    @Operation(summary = "新增用户")
    @RequiresPermissions("user:userInfo:add")
    @Log(title = "用户", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody UserInfo userInfo)
    {
        return toAjax(userInfoService.save(userInfo));
    }

    /**
     * 修改用户
     */
    @Operation(summary = "修改用户")
    @RequiresPermissions("user:userInfo:edit")
    @Log(title = "用户", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody UserInfo userInfo)
    {
        return toAjax(userInfoService.updateById(userInfo));
    }

    /**
     * 删除用户
     */
    @Operation(summary = "删除用户")
    @RequiresPermissions("user:userInfo:remove")
    @Log(title = "用户", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(userInfoService.removeBatchByIds(Arrays.asList(ids)));
    }

}