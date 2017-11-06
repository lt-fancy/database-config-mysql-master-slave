package com.sawallianc.config;

import com.github.pagehelper.PageHelper;
import com.google.common.collect.Maps;
import com.sawallianc.enums.DataSourceTypeEnum;
import com.sawallianc.util.SpringContextUtil;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.aspectj.apache.bcel.util.ClassLoaderRepository;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.bind.RelaxedPropertyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.annotation.Resource;
import javax.sql.DataSource;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
@AutoConfigureAfter({DataSourceConfiguration.class})
@MapperScan({"com.sawallianc.**.dao"})
public class MybatisConfiguration {

    private static Log logger = LogFactory.getLog(MybatisConfiguration.class);
    private static final String MAPPER_LOCATION = "classpath*:mapper/**/*Mapper.xml";
    @Value("${mysql.datasource.readSize}")
    private String readDataSourceSize;

    @Autowired
    @Qualifier("masterDataSource")
    private DataSource writeDataSource;
    @Autowired
    @Qualifier("slaveDataSource1")
    private DataSource readDataSource01;
    @Autowired
    @Qualifier("slaveDataSource2")
    private DataSource readDataSource02;

    @Bean
    public SqlSessionFactory sqlSessionFactoryBean() throws Exception{
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(roundRobinDataSourceProxy());
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        bean.setMapperLocations(resolver.getResources(MAPPER_LOCATION));
        PageHelper pageHelper = new PageHelper();
        Properties props = new Properties();
        props.setProperty("reasonable","true");
        props.setProperty("supportMethodArguments","true");
        props.setProperty("returnPageInfo","check");
        props.setProperty("params","count=countSql");
        props.setProperty("dialect","mysql");
        props.setProperty("rowBoundsWithCount","true");
        pageHelper.setProperties(props);
        bean.setPlugins(new Interceptor[]{pageHelper});
        return bean.getObject();
    }

    @Bean(name="roundRobinDataSourceProxy")
    public AbstractRoutingDataSource roundRobinDataSourceProxy() {

        Map<Object, Object> targetDataSources = Maps.newHashMapWithExpectedSize(Integer.parseInt(readDataSourceSize));
        //把所有数据库都放在targetDataSources中,注意key值要和determineCurrentLookupKey()中代码写的一至，
        //否则切换数据源时找不到正确的数据源
        targetDataSources.put(DataSourceTypeEnum.write.getType(), writeDataSource);
        targetDataSources.put(DataSourceTypeEnum.read.getType()+"1", readDataSource01);
        targetDataSources.put(DataSourceTypeEnum.read.getType()+"2", readDataSource02);

        final int readSize = Integer.parseInt(readDataSourceSize);
        //     MyAbstractRoutingDataSource proxy = new MyAbstractRoutingDataSource(readSize);

        //路由类，寻找对应的数据源
        AbstractRoutingDataSource proxy = new AbstractRoutingDataSource(){
            private AtomicInteger count = new AtomicInteger(0);
            /**
             * 这是AbstractRoutingDataSource类中的一个抽象方法，
             * 而它的返回值是你所要用的数据源dataSource的key值，有了这个key值，
             * targetDataSources就从中取出对应的DataSource，如果找不到，就用配置默认的数据源。
             */
            @Override
            protected Object determineCurrentLookupKey() {
                String typeKey = DataSourceContextHolder.getReadOrWrite();

                if(typeKey == null){
                    //  System.err.println("使用数据库write.............");
                    //    return DataSourceType.write.getType();
                    throw new NullPointerException("数据库路由时，决定使用哪个数据库源类型不能为空...");
                }

                if (typeKey.equals(DataSourceTypeEnum.write.getType())){
                    System.err.println("使用数据库write.............");
                    return DataSourceTypeEnum.write.getType();
                }

                //读库， 简单负载均衡
                int number = count.getAndAdd(1);
                int lookupKey = number % readSize;
                System.err.println("使用数据库read-"+(lookupKey+1));
                return DataSourceTypeEnum.read.getType()+(lookupKey+1);
            }
        };

        proxy.setDefaultTargetDataSource(writeDataSource);//默认库
        proxy.setTargetDataSources(targetDataSources);
        return proxy;
    }

    @Bean
    public SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }

    //事务管理
    @Bean
    public PlatformTransactionManager annotationDrivenTransactionManager() {
        return new DataSourceTransactionManager((DataSource) SpringContextUtil.getBean("roundRobinDataSourceProxy"));
    }
}
