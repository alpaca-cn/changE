package com.change.config;

import org.hibernate.community.dialect.SQLiteDialect;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.hibernate.type.SqlTypes;

/**
 * SQLite 方言：把 Hibernate 的类型名对齐到 V1–V4 实际写下的存储类。
 *
 * <p><b>为什么必须自己写一个。</b>本项目 <b>建表交给 Flyway、Hibernate 只校验</b>
 * （{@code ddl-auto: validate}），于是启动时 Hibernate 会拿「实体的类型名」和
 * 「库里的列类型名」逐列对比，不一致就抛 SchemaManagementException、上下文起不来。
 * 而 community 方言给出的名字与冻结的 schema 对不上：
 *
 * <pre>
 *   Hibernate 期望        库里实际写的（JDBC 元数据 TYPE_NAME）
 *   bigint      (Long)   INTEGER
 *   boolean     (boolean) INTEGER
 *   date        (LocalDate) TEXT
 *   timestamp   (LocalDateTime) TEXT
 *   double      (Double) REAL
 * </pre>
 *
 * <p>这不是实体映射错了，而是 SQLite 根本没有这些类型：它只有 INTEGER / REAL / TEXT /
 * BLOB 四个存储类（BIGINT、BOOLEAN、DATE、TIMESTAMP 都是其它数据库的名字）。方言是
 * 「这个数据库怎么存东西」唯一正确的表述位置：
 * <ul>
 *   <li>写在实体上（每个字段一个 {@code @Column(columnDefinition=...)} 或
 *       {@code @JdbcTypeCode}）要为当前四个实体加 18 处注解，之后每加一张表都要重新记得一次，
 *       漏一处就是启动失败；而且 {@code @JdbcTypeCode(INTEGER)} 会把 Long 主键按 32 位读写，
 *       SQLite 的 rowid 是 64 位，超过 2^31 时会静默截断。</li>
 *   <li>写在这里是一次性的：十五张表、以及后续所有实体都自动正确。</li>
 * </ul>
 *
 * <p>只影响类型名（DDL 生成与 validate 比对），不改变任何绑定/读取的 Java 类型——
 * Long 仍然是 64 位，日期仍然由 SQLiteDialect 的 ISO-8601 文本格式写入。
 *
 * <p>注意这里返回的必须是 schema 里逐字写下的类型名（小写即可，比对时忽略大小写）：
 * 校验的第二条判据是「期望名以实际 TYPE_NAME 开头」。REAL 这一条尤其反直觉——
 * JDBC 元数据给 REAL 列的 DATA_TYPE 是 FLOAT(6) 而不是 REAL(7)，但 TYPE_NAME 是 "REAL"，
 * 所以 {@code SqlTypes.DOUBLE} 必须映射成 "real" 而不是 "float"。
 */
public class ChangeSqliteDialect extends SQLiteDialect {

    /** 无参构造：Hibernate 在拿不到连接信息时的回退路径。 */
    public ChangeSqliteDialect() {
        super();
    }

    /**
     * 接收版本信息的构造：Hibernate 优先用这一个，SQLiteDialect 内部有按版本分支的行为
     * （如 3.0 之前用 char/nchar），少了它就会退回 DatabaseVersion.DEFAULT。
     * Java 不继承构造器，所以必须显式写出来。
     */
    public ChangeSqliteDialect(DialectResolutionInfo info) {
        super(info);
    }

    @Override
    protected String columnType(int sqlTypeCode) {
        switch (sqlTypeCode) {
            // 整数族：SQLite 的一种存储类，64 位有符号。Long 主键/外键（BIGINT）与
            // boolean（BOOLEAN）在库里都是 INTEGER —— 本项目用 INTEGER + CHECK/DEFAULT 表达布尔。
            case SqlTypes.BOOLEAN:
            case SqlTypes.TINYINT:
            case SqlTypes.SMALLINT:
            case SqlTypes.INTEGER:
            case SqlTypes.BIGINT:
                return "integer";

            // 浮点族：REAL 是 8 字节 IEEE 浮点，Double/Float 都落在这里。
            case SqlTypes.REAL:
            case SqlTypes.FLOAT:
            case SqlTypes.DOUBLE:
                return "real";

            // 文本族：字符串与所有日期/时间戳。SQLite 没有原生日期类型，本项目统一
            // 用 TEXT 存 ISO-8601（纯日期列另有 GLOB CHECK 保证形状，见 V2）。
            case SqlTypes.DATE:
            case SqlTypes.TIME:
            case SqlTypes.TIMESTAMP:
                return "text";

            // 其余（BLOB/VARCHAR 等）沿用 community 方言的判断。
            default:
                return super.columnType(sqlTypeCode);
        }
    }
}
