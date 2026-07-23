package com.share.rules.api;

import com.share.common.core.domain.R;
import com.share.rules.domain.FeeRule;
import com.share.rules.factory.RemoteFeeRuleFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 用户服务
 */
@FeignClient(contextId = "remoteFreeRuleService",
        value = "share-rule",
        fallbackFactory = RemoteFeeRuleFallbackFactory.class)
public interface RemoteFeeRuleService {
    @PostMapping("/feeRule/getFeeRuleList")
    public R<List<FeeRule>> getFeeRuleList(@RequestBody List<Long> feeRuleIdList);

    @GetMapping(value = "/feeRule/getFeeRule/{id}")
    public R<FeeRule> getFeeRule(@PathVariable("id") Long id);
}
