/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.geospatial.ip2geo.jobscheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import lombok.SneakyThrows;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.junit.Before;
import org.opensearch.OpenSearchException;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.common.SuppressForbidden;
import org.opensearch.geospatial.GeospatialTestHelper;
import org.opensearch.geospatial.ip2geo.Ip2GeoTestCase;
import org.opensearch.geospatial.ip2geo.common.DatasourceManifest;
import org.opensearch.geospatial.ip2geo.common.DatasourceState;
import org.opensearch.jobscheduler.spi.schedule.IntervalSchedule;

@SuppressForbidden(reason = "unit test")
public class DatasourceUpdateServiceTests extends Ip2GeoTestCase {
    private DatasourceUpdateService datasourceUpdateService;

    @Before
    public void init() {
        datasourceUpdateService = new DatasourceUpdateService(clusterService, datasourceDao, geoIpDataDao);
    }

    @SneakyThrows
    public void testUpdateOrCreateGeoIpData_whenHashValueIsSame_thenSkipUpdate() {
        File manifestFile = new File(this.getClass().getClassLoader().getResource("ip2geo/manifest.json").getFile());
        DatasourceManifest manifest = DatasourceManifest.Builder.build(manifestFile.toURI().toURL());

        Datasource datasource = new Datasource();
        datasource.setState(DatasourceState.AVAILABLE);
        datasource.getUpdateStats().setLastSkippedAt(null);
        datasource.getDatabase().setUpdatedAt(Instant.ofEpochMilli(manifest.getUpdatedAt()));
        datasource.getDatabase().setSha256Hash(manifest.getSha256Hash());
        datasource.setEndpoint(manifestFile.toURI().toURL().toExternalForm());

        // Run
        datasourceUpdateService.updateOrCreateGeoIpData(datasource, mock(Runnable.class));

        // Verify
        assertNotNull(datasource.getUpdateStats().getLastSkippedAt());
        verify(datasourceDao).updateDatasource(datasource);
    }

    @SneakyThrows
    public void testUpdateOrCreateGeoIpData_whenExpired_thenUpdate() {
        File manifestFile = new File(this.getClass().getClassLoader().getResource("ip2geo/manifest.json").getFile());
        DatasourceManifest manifest = DatasourceManifest.Builder.build(manifestFile.toURI().toURL());

        File sampleFile = new File(this.getClass().getClassLoader().getResource("ip2geo/sample_valid.csv").getFile());
        when(geoIpDataDao.getDatabaseReader(any())).thenReturn(CSVParser.parse(sampleFile, StandardCharsets.UTF_8, CSVFormat.RFC4180));

        Datasource datasource = new Datasource();
        datasource.setState(DatasourceState.AVAILABLE);
        datasource.getDatabase().setUpdatedAt(Instant.ofEpochMilli(manifest.getUpdatedAt()));
        datasource.getDatabase().setSha256Hash(manifest.getSha256Hash());
        datasource.getDatabase().setValidForInDays(1l);
        datasource.setEndpoint(manifestFile.toURI().toURL().toExternalForm());
        datasource.resetDatabase();

        // Run
        datasourceUpdateService.updateOrCreateGeoIpData(datasource, mock(Runnable.class));

        // Verify
        verify(geoIpDataDao).putGeoIpData(eq(datasource.currentIndexName()), isA(String[].class), any(Iterator.class), any(Runnable.class));
    }

    @SneakyThrows
    public void testUpdateOrCreateGeoIpData_whenInvalidData_thenThrowException() {
        File manifestFile = new File(this.getClass().getClassLoader().getResource("ip2geo/manifest.json").getFile());
        DatasourceManifest manifest = DatasourceManifest.Builder.build(manifestFile.toURI().toURL());

        File sampleFile = new File(
            this.getClass().getClassLoader().getResource("ip2geo/sample_invalid_less_than_two_fields.csv").getFile()
        );
        when(geoIpDataDao.getDatabaseReader(any())).thenReturn(CSVParser.parse(sampleFile, StandardCharsets.UTF_8, CSVFormat.RFC4180));

        Datasource datasource = new Datasource();
        datasource.setState(DatasourceState.AVAILABLE);
        datasource.getDatabase().setUpdatedAt(Instant.ofEpochMilli(manifest.getUpdatedAt() - 1));
        datasource.getDatabase().setSha256Hash(manifest.getSha256Hash().substring(1));
        datasource.getDatabase().setFields(Arrays.asList("country_name"));
        datasource.setEndpoint(manifestFile.toURI().toURL().toExternalForm());

        // Run
        expectThrows(OpenSearchException.class, () -> datasourceUpdateService.updateOrCreateGeoIpData(datasource, mock(Runnable.class)));
    }

    @SneakyThrows
    public void testUpdateOrCreateGeoIpData_whenIncompatibleFields_thenThrowException() {
        File manifestFile = new File(this.getClass().getClassLoader().getResource("ip2geo/manifest.json").getFile());
        DatasourceManifest manifest = DatasourceManifest.Builder.build(manifestFile.toURI().toURL());

        File sampleFile = new File(this.getClass().getClassLoader().getResource("ip2geo/sample_valid.csv").getFile());
        when(geoIpDataDao.getDatabaseReader(any())).thenReturn(CSVParser.parse(sampleFile, StandardCharsets.UTF_8, CSVFormat.RFC4180));

        Datasource datasource = new Datasource();
        datasource.setState(DatasourceState.AVAILABLE);
        datasource.getDatabase().setUpdatedAt(Instant.ofEpochMilli(manifest.getUpdatedAt() - 1));
        datasource.getDatabase().setSha256Hash(manifest.getSha256Hash().substring(1));
        datasource.getDatabase().setFields(Arrays.asList("country_name", "additional_field"));
        datasource.setEndpoint(manifestFile.toURI().toURL().toExternalForm());

        // Run
        expectThrows(OpenSearchException.class, () -> datasourceUpdateService.updateOrCreateGeoIpData(datasource, mock(Runnable.class)));
    }

    @SneakyThrows
    public void testUpdateOrCreateGeoIpData_whenValidInput_thenSucceed() {
        File manifestFile = new File(this.getClass().getClassLoader().getResource("ip2geo/manifest.json").getFile());
        DatasourceManifest manifest = DatasourceManifest.Builder.build(manifestFile.toURI().toURL());

        File sampleFile = new File(this.getClass().getClassLoader().getResource("ip2geo/sample_valid.csv").getFile());
        when(geoIpDataDao.getDatabaseReader(any())).thenReturn(CSVParser.parse(sampleFile, StandardCharsets.UTF_8, CSVFormat.RFC4180));
        ShardRouting shardRouting = mock(ShardRouting.class);
        when(shardRouting.started()).thenReturn(true);
        when(routingTable.allShards(anyString())).thenReturn(Arrays.asList(shardRouting));

        Datasource datasource = new Datasource();
        datasource.setState(DatasourceState.AVAILABLE);
        datasource.getDatabase().setUpdatedAt(Instant.ofEpochMilli(manifest.getUpdatedAt() - 1));
        datasource.getDatabase().setSha256Hash(manifest.getSha256Hash().substring(1));
        datasource.getDatabase().setFields(Arrays.asList("country_name"));
        datasource.setEndpoint(manifestFile.toURI().toURL().toExternalForm());
        datasource.getUpdateStats().setLastSucceededAt(null);
        datasource.getUpdateStats().setLastProcessingTimeInMillis(null);

        // Run
        datasourceUpdateService.updateOrCreateGeoIpData(datasource, mock(Runnable.class));

        // Verify
        assertEquals(manifest.getProvider(), datasource.getDatabase().getProvider());
        assertEquals(manifest.getSha256Hash(), datasource.getDatabase().getSha256Hash());
        assertEquals(Instant.ofEpochMilli(manifest.getUpdatedAt()), datasource.getDatabase().getUpdatedAt());
        assertEquals(manifest.getValidForInDays(), datasource.getDatabase().getValidForInDays());
        assertNotNull(datasource.getUpdateStats().getLastSucceededAt());
        assertNotNull(datasource.getUpdateStats().getLastProcessingTimeInMillis());
        verify(datasourceDao, times(2)).updateDatasource(datasource);
        verify(geoIpDataDao).putGeoIpData(eq(datasource.currentIndexName()), isA(String[].class), any(Iterator.class), any(Runnable.class));
    }

    public void testWaitUntilAllShardsStarted_whenTimedOut_thenThrowException() {
        String indexName = GeospatialTestHelper.randomLowerCaseString();
        ShardRouting shardRouting = mock(ShardRouting.class);
        when(shardRouting.started()).thenReturn(false);
        when(routingTable.allShards(indexName)).thenReturn(Arrays.asList(shardRouting));

        // Run
        Exception e = expectThrows(OpenSearchException.class, () -> datasourceUpdateService.waitUntilAllShardsStarted(indexName, 10));

        // Verify
        assertTrue(e.getMessage().contains("did not complete"));
    }

    @SneakyThrows
    public void testWaitUntilAllShardsStarted_whenInterrupted_thenThrowException() {
        String indexName = GeospatialTestHelper.randomLowerCaseString();
        ShardRouting shardRouting = mock(ShardRouting.class);
        when(shardRouting.started()).thenReturn(false);
        when(routingTable.allShards(indexName)).thenReturn(Arrays.asList(shardRouting));

        // Run
        Thread.currentThread().interrupt();
        Exception e = expectThrows(RuntimeException.class, () -> datasourceUpdateService.waitUntilAllShardsStarted(indexName, 10));

        // Verify
        assertEquals(InterruptedException.class, e.getCause().getClass());
    }

    @SneakyThrows
    public void testGetHeaderFields_whenValidInput_thenReturnCorrectValue() {
        File manifestFile = new File(this.getClass().getClassLoader().getResource("ip2geo/manifest.json").getFile());

        File sampleFile = new File(this.getClass().getClassLoader().getResource("ip2geo/sample_valid.csv").getFile());
        when(geoIpDataDao.getDatabaseReader(any())).thenReturn(CSVParser.parse(sampleFile, StandardCharsets.UTF_8, CSVFormat.RFC4180));

        // Run
        assertEquals(Arrays.asList("country_name"), datasourceUpdateService.getHeaderFields(manifestFile.toURI().toURL().toExternalForm()));
    }

    @SneakyThrows
    public void testDeleteUnusedIndices_whenValidInput_thenSucceed() {
        String datasourceName = GeospatialTestHelper.randomLowerCaseString();
        String indexPrefix = String.format(".ip2geo-data.%s.", datasourceName);
        Instant now = Instant.now();
        String currentIndex = indexPrefix + now.toEpochMilli();
        String oldIndex = indexPrefix + now.minusMillis(1).toEpochMilli();
        String lingeringIndex = indexPrefix + now.minusMillis(2).toEpochMilli();
        Datasource datasource = new Datasource();
        datasource.setName(datasourceName);
        datasource.setCurrentIndex(currentIndex);
        datasource.getIndices().add(currentIndex);
        datasource.getIndices().add(oldIndex);
        datasource.getIndices().add(lingeringIndex);
        datasource.getDatabase().setUpdatedAt(now);

        when(metadata.hasIndex(currentIndex)).thenReturn(true);
        when(metadata.hasIndex(oldIndex)).thenReturn(true);
        when(metadata.hasIndex(lingeringIndex)).thenReturn(false);

        datasourceUpdateService.deleteUnusedIndices(datasource);

        assertEquals(1, datasource.getIndices().size());
        assertEquals(currentIndex, datasource.getIndices().get(0));
        verify(datasourceDao).updateDatasource(datasource);
        verify(geoIpDataDao).deleteIp2GeoDataIndex(oldIndex);
    }

    public void testUpdateDatasource_whenNoChange_thenNoUpdate() {
        Datasource datasource = randomDatasource();

        // Run
        datasourceUpdateService.updateDatasource(datasource, datasource.getSystemSchedule(), datasource.getTask());

        // Verify
        verify(datasourceDao, never()).updateDatasource(any());
    }

    public void testUpdateDatasource_whenChange_thenUpdate() {
        Datasource datasource = randomDatasource();
        datasource.setTask(DatasourceTask.ALL);

        // Run
        datasourceUpdateService.updateDatasource(
            datasource,
            new IntervalSchedule(Instant.now(), datasource.getSystemSchedule().getInterval() + 1, ChronoUnit.DAYS),
            datasource.getTask()
        );
        datasourceUpdateService.updateDatasource(datasource, datasource.getSystemSchedule(), DatasourceTask.DELETE_UNUSED_INDICES);

        // Verify
        verify(datasourceDao, times(2)).updateDatasource(any());
    }

    @SneakyThrows
    public void testGetHeaderFields_whenValidInput_thenSucceed() {
        File manifestFile = new File(this.getClass().getClassLoader().getResource("ip2geo/manifest.json").getFile());
        File sampleFile = new File(this.getClass().getClassLoader().getResource("ip2geo/sample_valid.csv").getFile());
        when(geoIpDataDao.getDatabaseReader(any())).thenReturn(CSVParser.parse(sampleFile, StandardCharsets.UTF_8, CSVFormat.RFC4180));

        // Run
        List<String> fields = datasourceUpdateService.getHeaderFields(manifestFile.toURI().toURL().toExternalForm());

        // Verify
        List<String> expectedFields = Arrays.asList("country_name");
        assertEquals(expectedFields, fields);
    }
}
