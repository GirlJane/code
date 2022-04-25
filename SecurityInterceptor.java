
/**
 * 加密解密插件
 * sql拦截器

 * @date 2022/3/15 6:41 下午
 */
@Slf4j
@Intercepts({
        @Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}),
        @Signature(type = StatementHandler.class, method = "query", args = {Statement.class, ResultHandler.class})
})
@Component
public class SecurityInterceptor implements Interceptor {

    /**
     * 表和字段的映射关系
     * < 数据库表名 - <原始字段名 - SecurityColumnEntity> >
     */
    private Map<String, Map<String, SecurityColumnEntity>> tableColumnMap = new HashMap<>(128);

    /**
     * 用于辅助进行反射的字段缓存
     */
    private Field additionalParametersField;

    /**
     * 加密拦截器配置
     */
    @Resource
    private SecurityInterceptorConfig securityInterceptorConfig;

    /**
     * 列对象
     */
    private final ThreadLocal<Map<String, String>> columnPropertyMapThreadLocal = new ThreadLocal<>();
    /**
     * <原始字段名 - SecurityColumnEntity>
     */
    private final Map<String, SecurityColumnEntity> targetColumnMap = new HashMap<>(128);
    /**
     * Ducc 的配置
     */
    @Resource
    private DuccConfig duccConfig;

    /**
     * 查询所有字段：select *
     */
    private static final String ALL_COLUMN = "*";

    /**
     * --
     */
    public SecurityInterceptor() {
        try {
            //反射获取 BoundSql 中的 additionalParameters 属性
            additionalParametersField = BoundSql.class.getDeclaredField("additionalParameters");
            additionalParametersField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            log.error("【SQL】{}-{}",e.getMessage(),e);
            throw new RuntimeException(e.getMessage());
        }
    }

    @Override
    @UmpProfiler
    public Object intercept(Invocation invocation) throws Throwable {
        try {
            final Object target = invocation.getTarget();
            if (!(target instanceof StatementHandler)) {
                log.info("【SQL】target not instanceof StatementHandler");
                return invocation.proceed();
            }
            StatementHandler statementHandler = (StatementHandler) target;
            MetaObject metaObject = SystemMetaObject.forObject(statementHandler);

            String name = "delegate.mappedStatement";
            if(!metaObject.hasGetter(name)){
                log.info("【SQL】metaObject not hasGetter delegate.mappedStatement");
                return invocation.proceed();
            }
            final Object metaObjectToMappedStatement = metaObject.getValue(name);
            if (!(metaObjectToMappedStatement instanceof MappedStatement)) {
                log.info("【SQL】metaObjectToMappedStatement not instanceof MappedStatement");
                return invocation.proceed();
            }

            // 如果没有需要处理的字段，或者关闭加密 那么直接执行
            if (tableColumnMap == null || tableColumnMap.isEmpty() || !securityInterceptorConfig.isEnable()) {
                log.info("【SQL】=====> 不需要加解密");
                this.sqlPrint(metaObject);
                return invocation.proceed();
            }

            /*
             先拦截到RoutingStatementHandler，里面有个StatementHandler类型的delegate变量，
             其实现类是BaseStatementHandler，然后就到BaseStatementHandler的成员变量mappedStatement
             */
            // 获取ms对象
            MappedStatement ms = (MappedStatement) metaObjectToMappedStatement;
            String id = ms.getId();
            log.info("【SQL】statementIdPrefix: 【{}】", id);

            final Object metaObjectToConfiguration = metaObject.getValue("delegate.configuration");
            if (!(metaObjectToConfiguration instanceof Configuration)) {
                log.info("【SQL】metaObjectToConfiguration not instanceof Configuration");
                this.sqlPrint(metaObject);
                return invocation.proceed();
            }
            Configuration configuration = (Configuration) metaObjectToConfiguration;

            // 如果是预处理方法 进行sql处理
            String prepare = "prepare";
            if (prepare.equals(invocation.getMethod().getName())) {
                BoundSql boundSql = statementHandler.getBoundSql();
                AbstractSqlProcessor sqlProcessor = null;
                switch (ms.getSqlCommandType()) {
                    case INSERT:
                        sqlProcessor = new InsertSqlProcessor(boundSql.getSql(), ms);
                        break;
                    case UPDATE:
                        sqlProcessor = new UpdateSqlProcessor(boundSql.getSql(), ms);
                        break;
                    case SELECT:
                        sqlProcessor = new SelectSqlProcessor(boundSql.getSql(), ms);
                        break;
                    case DELETE:
                        sqlProcessor = new DeleteSqlProcessor(boundSql.getSql(), ms);
                    default:
                        log.error("【SQL】no sqlCommandType");
                        break;
                }

                if (null == sqlProcessor) {
                    log.info("【SQL】null == sqlProcessor");
                    this.sqlPrint(metaObject);
                    return invocation.proceed();
                }

                sqlProcessor.setBaseSecurityHandler(securityInterceptorConfig.getSecurityHandler());
                sqlProcessor.setOverwritePlainByIndex(securityInterceptorConfig.isOverwritePlainByIndex());
                sqlProcessor.setWriteIndexToPlain(securityInterceptorConfig.isWriteIndexToPlain());
                // 获取到本次sql涉及的表
                List<String> tablesNames = sqlProcessor.getTablesNames();
                Map<String, String> tablesNamesAndAlias = sqlProcessor.getTablesNamesAndAlias();
                Map<String, String> tablesNamesAndAliasNew = new HashMap<>(128);
                // 获取到需要处理的表
                Set<String> needProcessTables = tableColumnMap.keySet();

                Map<String, SecurityColumnEntity> sourceTargetMap = new HashMap<>(128);
                boolean needProcess = false;
                for (String tablesName : tablesNames) {
                    if (needProcessTables.contains(tablesName)) {
                        needProcess = true;
                        sourceTargetMap.putAll(tableColumnMap.get(tablesName));
                        if (tablesNamesAndAlias != null) {
                            tablesNamesAndAliasNew.put(tablesName, tablesNamesAndAlias.get(tablesName));
                        }
                    }
                }
                if (!needProcess) {
                    log.info("【SQL】sql not need encrypt");
                    this.sqlPrint(metaObject);
                    return invocation.proceed();
                }
                sqlProcessor.setTableSourceColumnMap(tableColumnMap);
                sqlProcessor.setTablesNamesAndAlias(tablesNamesAndAliasNew);
                // 创建一个新的 bound sql 来进行处理 防止处理后影响其他的逻辑
                Map<String, Object> additionalParameters = (Map<String, Object>) additionalParametersField.get(boundSql);
                BoundSql newBoundSql = new BoundSql(ms.getConfiguration(), boundSql.getSql(), new ArrayList<>(boundSql.getParameterMappings()), boundSql.getParameterObject());
                for (String key : additionalParameters.keySet()) {
                    newBoundSql.setAdditionalParameter(key, additionalParameters.get(key));
                }

                sqlProcessor.process(newBoundSql, sourceTargetMap);
                if (sqlProcessor instanceof SelectSqlProcessor) {
                    columnPropertyMapThreadLocal.set(((SelectSqlProcessor) sqlProcessor).getColumnPropertyMap());
                    targetColumnMap.putAll(sourceTargetMap);
                }
                ParameterHandler parameterHandler = configuration.newParameterHandler(ms, newBoundSql.getParameterObject(), newBoundSql);
                metaObject.setValue("delegate.boundSql", newBoundSql);
                metaObject.setValue("delegate.parameterHandler", parameterHandler);

                this.sqlPrint(metaObject);
                return invocation.proceed();
            } else {
                // 如果是查询操作 进行数据的解密映射
                Object result = invocation.proceed();
                Map<String, String> columnPropertyMap = columnPropertyMapThreadLocal.get();
                if (columnPropertyMap == null) {
                    columnPropertyMap = new HashMap<>(16);
                    columnPropertyMapThreadLocal.set(columnPropertyMap);
                }
                if (columnPropertyMap.size() > 0 && result instanceof List) {
                    List resultArr = (List) result;
                    Collection<String> properties = columnPropertyMap.keySet();
                    if (properties.contains(ALL_COLUMN)) {
                        log.info("【SQL】sql contains *");
                        properties = targetColumnMap.keySet();
                    }

                    for (Object o : resultArr) {
                        MetaObject mo = configuration.newMetaObject(o);
                        for (String pro : properties) {
                            String source = mo.findProperty(pro, configuration.isMapUnderscoreToCamelCase());
                            SecurityColumnEntity securityColumnEntity = targetColumnMap.get(pro);
                            String property = mo.findProperty(securityColumnEntity.getTargetColumn(), configuration.isMapUnderscoreToCamelCase());
                            Object value;
                            try {
                                value = mo.getValue(property);
                            } catch (Exception e) {
                                log.error("【SQL】{}",e.getMessage(),e);
                                value = null;
                            }
                            if (value != null) {
                                String decrypt = securityInterceptorConfig.getSecurityHandler().decrypt(value.toString());
                                mo.setValue(source, decrypt);
                            }
                        }
                    }
                }
                targetColumnMap.clear();
                columnPropertyMapThreadLocal.remove();
                return result;
            }
        } catch (Exception e) {
            log.error("【SQL】{}", e.getMessage(),e);
            return invocation.proceed();
        }
    }

    @Override
    public Object plugin(Object target) {
        if (target instanceof StatementHandler) {
            return Plugin.wrap(target, this);
        }
        return target;
    }



    /**
     * --
     */
    public Field getAdditionalParametersField() {
        return additionalParametersField;
    }

    /**
     * --
     */
    public void setAdditionalParametersField(Field additionalParametersField) {
        this.additionalParametersField = additionalParametersField;
    }



    /**
     * sql打印
     */
    private void sqlPrint(MetaObject metaObjectHandler) {
        long start = DateUtils.currentTime();
        long end = DateUtils.currentTime();
        try {
            if (null != metaObjectHandler) {
                final Boolean isPrintSql = duccConfig.getBooleanConfig(DuccGlobalConstant.IS_PRINT_SQL_LOG);
                if (isPrintSql) {
                    // 获取查询接口映射的相关信息
                    String name = "delegate.mappedStatement";
                    if (metaObjectHandler.hasGetter(name)) {
                        final Object metaObjectHandlerValue = metaObjectHandler.getValue(name);
                        if (metaObjectHandlerValue instanceof MappedStatement) {
                            MappedStatement mappedStatement = (MappedStatement) metaObjectHandlerValue;
                            // 获取请求时的参数
                            final Object metaObjectToBoundSql = metaObjectHandler.getValue("delegate.boundSql");
                            if (metaObjectToBoundSql instanceof BoundSql) {
                                BoundSql boundSql = (BoundSql) metaObjectToBoundSql;
                                // 获取sql
                                String sql = showSql(mappedStatement.getConfiguration(), boundSql);
                                // 获取执行sql方法
                                String sqlId = mappedStatement.getId();
                                log.info("【SQL】({})--> {}", sqlId, sql);
                                // String formatSql = SQLUtils.format(sql, JdbcConstants.MYSQL);
                                // log.info("【SQL-FORMAT】({})-->{}", sqlId, formatSql);
                                log.info("【SQL】({})--> {} ms", sqlId, end - start);
                            }
                        }
                    }
                }
            } else {
                log.error("【SQL】invocation error");
            }
        } catch (Exception e) {
            log.error("【SQL】{}", e.getMessage(), e);
        }
    }

    /**
     * getSql
     */
    private static String showSql(Configuration configuration, BoundSql boundSql) {
        // 获取参数
        Object parameterObject = boundSql.getParameterObject();
        List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
        // sql语句中多个空格都用一个空格代替
        String sql = boundSql.getSql().replaceAll("[\\s]+", " ");
        if (CollectionUtils.isNotEmpty(parameterMappings) && parameterObject != null) {
            // 获取类型处理器注册器，类型处理器的功能是进行java类型和数据库类型的转换
            TypeHandlerRegistry typeHandlerRegistry = configuration.getTypeHandlerRegistry();
            // 如果根据parameterObject.getClass(）可以找到对应的类型，则替换
            if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
                sql = sql.replaceFirst("\\?", Matcher.quoteReplacement(getParameterValue(parameterObject)));
            } else {
                // MetaObject主要是封装了originalObject对象，提供了get和set的方法用于获取和设置originalObject的属性值,主要支持对JavaBean、Collection、Map三种类型对象的操作
                MetaObject metaObject = configuration.newMetaObject(parameterObject);
                for (ParameterMapping parameterMapping : parameterMappings) {
                    String propertyName = parameterMapping.getProperty();
                    if (metaObject.hasGetter(propertyName)) {
                        Object obj = metaObject.getValue(propertyName);
                        sql = sql.replaceFirst("\\?", Matcher.quoteReplacement(getParameterValue(obj)));
                    } else if (boundSql.hasAdditionalParameter(propertyName)) {
                        // 该分支是动态sql
                        Object obj = boundSql.getAdditionalParameter(propertyName);
                        sql = sql.replaceFirst("\\?", Matcher.quoteReplacement(getParameterValue(obj)));
                    } else {
                        // 打印出缺失，提醒该参数缺失并防止错位
                        sql = sql.replaceFirst("\\?", "缺失");
                    }
                }
            }
        }

        return sql;
    }

    /**
     * 获取SQL参数
     */
    private static String getParameterValue(Object obj) {
        String value;
        if (obj instanceof String) {
            value = String.format("'%s'", obj);
        } else if (obj instanceof Date) {
            DateFormat formatter = DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT, Locale.CHINA);
            value = "'" + formatter.format(new Date()) + "'";
        } else {
            if (obj != null) {
                value = obj.toString();
            } else {
                log.error("【SQL】getParameterValue param obj is null");
                value = "";
            }
        }
        return value;
    }
}
