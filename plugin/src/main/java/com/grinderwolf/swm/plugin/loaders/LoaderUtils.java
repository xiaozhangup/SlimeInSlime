package com.grinderwolf.swm.plugin.loaders;

import com.grinderwolf.swm.plugin.config.*;
import com.grinderwolf.swm.plugin.loaders.mongo.MongoLoader;
import com.grinderwolf.swm.plugin.loaders.mysql.MysqlLoader;
import com.grinderwolf.swm.plugin.loaders.redis.RedisLoader;
import com.grinderwolf.swm.plugin.log.Logging;
import com.infernalsuite.aswm.api.loaders.SlimeLoader;

import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class LoaderUtils {

    public static final long MAX_LOCK_TIME = 300000L; // Max time difference between current time millis and world lock
    public static final long LOCK_INTERVAL = 60000L;

    private static final Map<DataSource, SlimeLoader> loaderMap = new HashMap<>();

    public static void registerLoaders(DataSourceConfig config) throws SQLException {
        SlimeLoader loadSystem = switch (config.getType()) {
            case MYSQL -> new MysqlLoader((MysqlConfig) config);
            case MONGO -> new MongoLoader((MongoDBConfig) config);
            case REDIS -> new RedisLoader((RedisConfig) config);
        };

        registerLoader(config.getType(), loadSystem);
    }

    public static List<DataSource> getAvailableLoadersNames() {
        return new LinkedList<>(loaderMap.keySet());
    }


    public static SlimeLoader getLoader() {
        return loaderMap.values().stream().findFirst().get();
    }

    public static void registerLoader(DataSource dataSource, SlimeLoader loader) {
        if (loaderMap.containsKey(dataSource)) {
            throw new IllegalArgumentException("Data source " + dataSource + " already has a declared loader!");
        }

        if (loader instanceof UpdatableLoader) {
            try {
                ((UpdatableLoader) loader).update();
            } catch (UpdatableLoader.NewerDatabaseException e) {
                Logging.error("Data source " + dataSource + " version is " + e.getDatabaseVersion() + ", while" +
                        " this SWM version only supports up to version " + e.getCurrentVersion() + ".");
                return;
            } catch (IOException ex) {
                Logging.error("Failed to check if data source " + dataSource + " is updated:");
                ex.printStackTrace();
                return;
            }
        }

        loaderMap.put(dataSource, loader);
    }

}
