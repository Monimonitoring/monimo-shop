package com.monimo.shop.order

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import java.sql.Statement

/**
 * orders 표 접근. SQL 을 직접 쓴다.
 * OTel Java Agent 의 JDBC 계측이 이 쿼리들을 스팬으로 남겨 Service map 에 MySQL 노드가 생긴다. 여기엔 계측 코드가 없다.
 */
@Repository
class OrderRepository(private val jdbc: JdbcTemplate) {

    /** PENDING 으로 한 줄 넣고 생성된 id 를 돌려준다. */
    fun insertPending(req: CreateOrderRequest): Long {
        val keys = GeneratedKeyHolder()
        jdbc.update({ con ->
            con.prepareStatement(
                "INSERT INTO orders (product_id, quantity, amount, status) VALUES (?, ?, ?, 'PENDING')",
                Statement.RETURN_GENERATED_KEYS,
            ).apply {
                setString(1, req.productId)
                setInt(2, req.quantity)
                setInt(3, req.amount)
            }
        }, keys)
        return keys.key!!.toLong()
    }

    fun updateResult(id: Long, status: OrderStatus, paymentId: String?) {
        jdbc.update("UPDATE orders SET status = ?, payment_id = ? WHERE id = ?", status.name, paymentId, id)
    }

    fun findById(id: Long): Order? =
        jdbc.query(
            "SELECT id, product_id, quantity, amount, status, payment_id, created_at FROM orders WHERE id = ?",
            { rs, _ ->
                Order(
                    id = rs.getLong("id"),
                    productId = rs.getString("product_id"),
                    quantity = rs.getInt("quantity"),
                    amount = rs.getInt("amount"),
                    status = OrderStatus.valueOf(rs.getString("status")),
                    paymentId = rs.getString("payment_id"),
                    createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
                )
            },
            id,
        ).firstOrNull()
}
