package com.qizhi.workorder.mapper;

import com.qizhi.workorder.entity.OperationLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;

/**
 * 使用工单服务的 Seata 代理数据源跨库写入 qizhi_log。
 */
public interface OperationLogMapper {

    @Insert("""
            INSERT INTO qizhi_log.operate_log
                (user_id, user_name, module, action, target_type, target_id,
                 detail, seata_xid, created_at)
            VALUES
                (#{userId}, #{userName}, #{module}, #{action}, #{targetType},
                 #{targetId}, #{detail}, #{seataXid}, #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(OperationLog operationLog);
}
