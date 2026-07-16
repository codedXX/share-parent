package com.share.rules.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.share.rule.domain.FeeRule;

import java.util.List;

/**
 * 费用规则Mapper接口
 *
 * @author atguigu
 * @date 2024-10-25
 */
public interface FeeRuleMapper extends BaseMapper<FeeRule>
{
    public List<FeeRule> selectFeeRuleList(FeeRule feeRule);
}

