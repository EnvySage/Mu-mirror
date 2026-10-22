package org.xianshen.mumirrorb.agent;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.xianshen.mumirrorb.mapper.AgentRunMapper;

import javax.sql.DataSource;

/** Builds the production mapper without starting a full Spring context. */
final class AgentRunMapperTestSupport {

    private AgentRunMapperTestSupport() {
    }

    static AgentRunMapper mapper(DataSource dataSource) {
        try {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            SqlSessionFactory sessionFactory = factory.getObject();
            if (sessionFactory == null) {
                throw new IllegalStateException("MyBatis SqlSessionFactory was not created");
            }
            return new SqlSessionTemplate(sessionFactory).getMapper(AgentRunMapper.class);
        } catch (Exception error) {
            throw new IllegalStateException("Could not create AgentRunMapper for integration test", error);
        }
    }
}
