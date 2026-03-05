package com.bba.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bba.entity.PolicyContract;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Mapper;

import java.math.BigDecimal;

@Mapper
public interface PolicyContractMapper extends BaseMapper<PolicyContract> {

    @Select(
            "SELECT SUM(COALESCE(\"合计费用\", 0)) AS iacf_amount " +
            "FROM zh.summary_iacf_cost " +
            "WHERE \"保单号\" = #{policyNo, jdbcType=VARCHAR} " +
            "  AND ( " +
            "        ((#{certiNo, jdbcType=VARCHAR} IS NULL OR #{certiNo, jdbcType=VARCHAR} = '') AND (\"批单号\" IS NULL OR COALESCE(\"批单号\"::text, '') = '')) " +
            "        OR " +
            "        ((#{certiNo, jdbcType=VARCHAR} IS NOT NULL AND #{certiNo, jdbcType=VARCHAR} <> '') AND \"批单号\" = #{certiNo, jdbcType=VARCHAR}) " +
            "      )"
    )
    BigDecimal selectIacfAmount(@Param("policyNo") String policyNo, @Param("certiNo") String certiNo);
}
