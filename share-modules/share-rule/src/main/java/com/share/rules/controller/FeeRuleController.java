package com.share.rules.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.share.common.security.annotation.RequiresPermissions;
import com.share.rules.service.IFeeRuleService;
import com.share.common.core.web.controller.BaseController;
import com.share.common.core.web.domain.AjaxResult;
import com.share.common.core.utils.poi.ExcelUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.share.common.core.web.page.TableDataInfo;

/**
 * 费用规则Controller
 *
 * @author atguigu
 * @date 2024-10-25
 */
@Tag(name = "费用规则接口管理")
@RestController
@RequestMapping("/feeRule")
public class FeeRuleController extends BaseController
{
    @Autowired
    private IFeeRuleService feeRuleService;

}

