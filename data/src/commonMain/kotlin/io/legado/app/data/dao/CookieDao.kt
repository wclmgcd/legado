package io.legado.app.data.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import io.legado.app.data.entities.Cookie

@Dao
interface CookieDao {

    @Query("SELECT * FROM cookies Where url = :url")
    suspend fun get(url: String): Cookie?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg cookie: Cookie)

    @Query("delete from cookies where url = :url")
    suspend fun delete(url: String)

    @Query("delete from cookies where url like '%|%'")
    suspend fun deleteOkHttp()

    /**
     * 列出全部**按域名**存的 cookie。
     *
     * 排除 OkHttp cookie jar 的条目: 那批的 `url` 是 `域名|cookie名` 形态
     * (见 [deleteOkHttp]), 一个域名会有几十条, 拿去做整表同步既没意义也会把
     * 域名当 key 覆盖掉正常记录。
     */
    @Query("select * from cookies where url not like '%|%'")
    suspend fun allByDomain(): List<Cookie>
}
