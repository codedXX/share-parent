import request from '@/utils/request'

export function getALLFeeRuleList() {
  return request({
    url: '/rule/feeRule/getALLFeeRuleList',
    method: 'get'
  })
}