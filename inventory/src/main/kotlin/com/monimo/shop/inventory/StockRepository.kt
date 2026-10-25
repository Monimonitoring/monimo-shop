package com.monimo.shop.inventory

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

/**
 * stock · stock_deductions 표 접근. SQL 을 직접 쓴다.
 * OTel Java Agent 의 JDBC 계측이 이 쿼리들을 스팬으로 남겨 Service map 에 inventory → MySQL 노드가 생긴다. 여기엔 계측 코드가 없다.
 */
@Repository
class StockRepository(private val jdbc: JdbcTemplate) {

    fun findQty(productId: String): Int? =
        jdbc.query("SELECT qty FROM stock WHERE product_id = ?", { rs, _ -> rs.getInt("qty") }, productId).firstOrNull()

    /**
     * 조건부 UPDATE 로 확인과 차감을 한 줄에 한다 (research ⑧ : a1).
     * MySQL 이 그 행에 락을 걸고 qty >= ? 를 보장하므로 두 주문이 동시에 와도 재고가 음수가 되지 않는다.
     * 바뀐 행이 0 이면 재고가 모자란 것 (상품이 없어도 0 이라 호출하는 쪽이 findQty 로 404 와 가른다)
     */
    fun deductIfEnough(productId: String, quantity: Int): Boolean =
        jdbc.update("UPDATE stock SET qty = qty - ? WHERE product_id = ? AND qty >= ?", quantity, productId, quantity) == 1

    fun restore(productId: String, quantity: Int) {
        jdbc.update("UPDATE stock SET qty = qty + ? WHERE product_id = ?", quantity, productId)
    }

    /** 차감 기록 한 줄. order_id 가 PK 라 같은 주문이 두 번 들어오면 false (이미 차감됨). */
    fun insertDeduction(orderId: Long, productId: String, quantity: Int): Boolean =
        try {
            jdbc.update("INSERT INTO stock_deductions (order_id, product_id, quantity) VALUES (?, ?, ?)", orderId, productId, quantity)
            true
        } catch (e: DuplicateKeyException) {
            false
        }

    /** 복원할 때 무엇을 얼마나 더할지. 이미 복원됐는지는 markRestored 가 UPDATE 한 줄로 판단하므로 여기서 읽지 않는다 */
    data class Deduction(val orderId: Long, val productId: String, val quantity: Int)

    fun findDeduction(orderId: Long): Deduction? =
        jdbc.query(
            "SELECT order_id, product_id, quantity FROM stock_deductions WHERE order_id = ?",
            { rs, _ -> Deduction(rs.getLong("order_id"), rs.getString("product_id"), rs.getInt("quantity")) },
            orderId,
        ).firstOrNull()

    /** restored 가 0 인 줄만 1 로 바꾼다. 바뀐 행이 0 이면 이미 복원된 주문 (두 번 더하지 않는다). */
    fun markRestored(orderId: Long): Boolean =
        jdbc.update("UPDATE stock_deductions SET restored = 1 WHERE order_id = ? AND restored = 0", orderId) == 1
}
