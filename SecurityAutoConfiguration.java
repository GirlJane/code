
/**
 * {@link EnableAutoConfiguration Auto-Configuration} for Mybatis. Contributes a
 * {@link SqlSessionFactory} and a {@link SqlSessionTemplate}.
 *
 * If {@link org.mybatis.spring.annotation.MapperScan} is used, or a
 * configuration file is specified as a property, those will be considered,
 * otherwise this auto-configuration will attempt to register mappers based on
 * the interface definitions in or under the root auto-configuration package.

 * @date 2020/12/28 11:00
 */
@Slf4j
@Configuration
@AutoConfigureAfter(name = "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration")
public class SecurityAutoConfiguration {

    /**
     * 加密表-字段配置
     */
    protected String securityTable;

    /**
     * 预加载需要加密的表、字段
     * @return 加密拦截器Bean
     */
    @Bean
    public SecurityInterceptor securityInterceptor(SecurityInterceptorConfig securityInterceptorConfig, AcesSecurityHandler acesSecurityHandler) {
        securityInterceptorConfig.setSecurityHandler(acesSecurityHandler);

        SecurityToBean securityToBean = new SecurityToBean();
        if(StringUtils.isNotEmpty(securityTable)){
            securityToBean = JSON.parseObject(securityTable, SecurityToBean.class);
        }
        securityInterceptorConfig.setSecurityColumnConfig(securityToBean.getSecurityTables());
        securityInterceptorConfig.setOverwritePlainByIndex(securityToBean.isOverwritePlainByIndex());
        securityInterceptorConfig.setEnable(securityToBean.isEnable());
        securityInterceptorConfig.setWriteIndexToPlain(securityToBean.isWriteIndexToPlain());

        SecurityInterceptor securityInterceptor = new SecurityInterceptor();
        securityInterceptor.setSecurityInterceptorConfig(securityInterceptorConfig);
        return securityInterceptor;
    }

}
