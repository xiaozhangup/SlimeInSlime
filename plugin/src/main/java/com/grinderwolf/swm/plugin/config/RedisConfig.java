package com.grinderwolf.swm.plugin.config;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@AllArgsConstructor
public class RedisConfig implements DataSourceConfig {

    @Setter
    @Getter
    private String uri = "redis://127.0.0.1/";

    @Override
    public final DataSource getType() {
        return DataSource.REDIS;
    }

}
