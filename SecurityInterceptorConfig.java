/**
 * config的配置
 * @date 2022/3/15 6:41 下午
 */
@Data
@Component
public class SecurityInterceptorConfig {

    /**
     * 使用index值覆盖原值 包括where条件和select返回值
     */
    private boolean overwritePlainByIndex = false;

    /**
     * 是否将index值写入 source，保留原字段密文存储
     */
    private boolean writeIndexToPlain = false;

    /**
     * 是否开启加解密
     */
    private boolean enable = false;

    /**
     * 加密字段配置
     */
    List<SecurityColumn> securityColumnConfig = new ArrayList<>();
}
