package com.xy.interview.demo.exportlab;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ExportDataMapper {

    @Select("""
            SELECT id, order_no, customer_name, region, amount, status, description, created_at
            FROM export_demo_data
            WHERE id > #{lastId} AND id <= #{upperBound}
            ORDER BY id
            LIMIT #{pageSize}
            """)
    List<ExportDataRow> queryPage(@Param("lastId") long lastId,
                                  @Param("upperBound") long upperBound,
                                  @Param("pageSize") int pageSize);

    @Select("""
            SELECT id, order_no, customer_name, region, amount, status, description, created_at
            FROM export_demo_data
            WHERE id <= #{upperBound}
            ORDER BY customer_name, id
            LIMIT #{offset}, #{pageSize}
            """)
    List<ExportDataRow> queryUnindexedOffsetPage(@Param("offset") long offset,
                                                 @Param("upperBound") long upperBound,
                                                 @Param("pageSize") int pageSize);

    @Select("""
            SELECT id, order_no, customer_name, region, amount, status, description, created_at
            FROM export_demo_data
            WHERE id > #{lastId}
            ORDER BY id
            LIMIT #{limit}
            """)
    List<ExportDataRow> queryBusinessProbe(@Param("lastId") long lastId,
                                           @Param("limit") int limit);

    @Select("SELECT COALESCE(MAX(id), 0) FROM export_demo_data")
    long maxId();

    @Select("SELECT COUNT(*) FROM export_demo_data")
    long countRows();

    @Select("SELECT 1")
    int probe();

    @Insert("""
            <script>
            INSERT INTO export_demo_data
                (order_no, customer_name, region, amount, status, description, created_at)
            VALUES
            <foreach collection="rows" item="row" separator=",">
                (#{row.orderNo}, #{row.customerName}, #{row.region}, #{row.amount},
                 #{row.status}, #{row.description}, #{row.createdAt})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("rows") List<ExportSeedRow> rows);
}
