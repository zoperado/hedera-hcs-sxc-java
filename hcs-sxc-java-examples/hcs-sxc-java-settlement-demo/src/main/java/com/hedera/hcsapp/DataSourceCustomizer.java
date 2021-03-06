package com.hedera.hcsapp;

/*-
 * ‌
 * hcs-sxc-java
 * ​
 * Copyright (C) 2019 - 2020 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import javax.sql.DataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import lombok.extern.log4j.Log4j2;

@Log4j2
@Configuration
public class DataSourceCustomizer {
    @Bean(name = "dataSourceOverride")
    @Primary
    @ConfigurationProperties(prefix="spring.datasource")
    public DataSource masterDataSource() {
        DataSource ds = null;
        try {
             ds = DataSourceBuilder.create()
                    .url("jdbc:h2:./h2data/demo-db-appid-"+Statics.getAppData().getAppId()+"-"+Statics.getAppData().getUserName())
                    .build();
        } catch (Exception ex) {
            log.error(ex);
        }
        return ds;
    }
 
}
