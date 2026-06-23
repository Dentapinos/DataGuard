package com.dentapinos.dataguard.test.config;//package com.dentapinos.dataguard.test.config;
//
//
//import com.dentapinos.dataguard.dto.DbCredentials;
//import com.dentapinos.dataguard.exception.DatabaseNotFoundException;
//import com.dentapinos.dataguard.utils.DatabaseConfigResolver;
//import org.junit.jupiter.api.Test;
//
//import java.util.List;
//
//import static org.junit.jupiter.api.Assertions.*;
//
//class DatabaseConfigResolverTest {
//
//    @Test
//    void resolveCredentials_returnsCredentials_whenDbConfigured() {
//        // given
//        BackupDatabasesProperties props = new BackupDatabasesProperties();
//
//        BackupDatabasesProperties.DatabaseConfig db = new BackupDatabasesProperties.DatabaseConfig();
//        db.setDatabaseName("bm");
//        db.setDisplayName("auth-service");
//        db.setUrl("jdbc:mysql://localhost:3306/bm");
//        db.setUsername("user");
//        db.setPassword("pass");
//
//        props.setDatabases(List.of(db));
//
//        DatabaseConfigResolver resolver = new DatabaseConfigResolver(props);
//
//        // when
//        DbCredentials credentials = resolver.resolveCredentials("bm");
//
//        // then
//        assertEquals("jdbc:mysql://localhost:3306/bm", credentials.url());
//        assertEquals("user", credentials.username());
//        assertEquals("pass", credentials.password());
//    }
//
//    @Test
//    void resolveCredentials_throwsException_whenDbNotConfigured() {
//        BackupDatabasesProperties props = new BackupDatabasesProperties();
//        DatabaseConfigResolver resolver = new DatabaseConfigResolver(props);
//
//        assertThrows(DatabaseNotFoundException.class,
//                () -> resolver.resolveCredentials("unknown"));
//    }
//}
