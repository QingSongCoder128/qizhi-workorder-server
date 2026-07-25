package com.qizhi.workorder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qizhi.workorder.entity.WorkOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface WorkOrderMapper extends BaseMapper<WorkOrder> {

    /**
     * 查询指定日期已存在的最大工单序号（order_no = WO+yyyyMMdd+6位序号）
     * 用于 Redis 序号计数器缺失时从数据库初始化，避免与已有工单编号冲突
     *
     * @param dateStr 日期串（yyyyMMdd）
     * @return 当天最大序号，无数据时返回 null
     */
    @Select("SELECT MAX(CAST(SUBSTRING(order_no, 11) AS UNSIGNED)) FROM work_order " +
            "WHERE order_no LIKE CONCAT('WO', #{dateStr}, '%')")
    Long selectMaxSeqByDate(@Param("dateStr") String dateStr);
}
