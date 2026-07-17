package com.share.rule.factory;

import com.share.common.core.domain.R;
import com.share.common.core.exception.ServiceException;
import com.share.rule.api.RemoteFeeRuleService;
import com.share.rule.domain.FeeRule;
import com.share.rule.domain.FeeRuleRequestForm;
import com.share.rule.domain.FeeRuleResponseVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用户服务降级处理
 *
 * @author share
 */
@Component
public class RemoteFeeRuleFallbackFactory implements FallbackFactory<RemoteFeeRuleService>
{
    private static final Logger log = LoggerFactory.getLogger(RemoteFeeRuleFallbackFactory.class);

    @Override
    public RemoteFeeRuleService create(Throwable throwable) {
        log.error("规则服务调用失败:{}", throwable.getMessage());
        return new RemoteFeeRuleService() {

            @Override
            public R<List<FeeRule>> getFeeRuleList(List<Long> feeRuleIdList) {
                return R.fail("获取费用规则列表失败:" + throwable.getMessage());
            }

            @Override
            public R<FeeRule> getFeeRule(Long id) {
                return R.fail("获取费用规则信息失败:" + throwable.getMessage());
            }

            @Override
            public R<FeeRuleResponseVo> calculateOrderFee(FeeRuleRequestForm feeRuleRequestForm) {
                return R.fail("计算订单费用失败:" + throwable.getMessage());
            }

        };
    }
}
