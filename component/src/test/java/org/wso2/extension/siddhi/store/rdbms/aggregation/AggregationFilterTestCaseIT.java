/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.extension.siddhi.store.rdbms.aggregation;

import org.apache.log4j.Logger;
import org.testng.AssertJUnit;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.wso2.extension.siddhi.store.rdbms.util.RDBMSTableTestUtils;
import org.wso2.siddhi.core.SiddhiAppRuntime;
import org.wso2.siddhi.core.SiddhiManager;
import org.wso2.siddhi.core.event.Event;
import org.wso2.siddhi.core.query.output.callback.QueryCallback;
import org.wso2.siddhi.core.stream.input.InputHandler;
import org.wso2.siddhi.core.util.EventPrinter;
import org.wso2.siddhi.core.util.SiddhiTestHelper;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.wso2.extension.siddhi.store.rdbms.util.RDBMSTableTestUtils.driverClassName;
import static org.wso2.extension.siddhi.store.rdbms.util.RDBMSTableTestUtils.password;
import static org.wso2.extension.siddhi.store.rdbms.util.RDBMSTableTestUtils.url;
import static org.wso2.extension.siddhi.store.rdbms.util.RDBMSTableTestUtils.user;

public class AggregationFilterTestCaseIT {

    private static final Logger LOG = Logger.getLogger(AggregationFilterTestCaseIT.class);
    private AtomicInteger inEventCount;
    private boolean eventArrived;
    private List<Object[]> inEventsList;

    @BeforeMethod
    public void init() {
        inEventCount = new AtomicInteger(0);
        eventArrived = false;
        inEventsList = new ArrayList<>();

        try {
            RDBMSTableTestUtils.initDatabaseTable("stockAggregation_SECONDS");
            RDBMSTableTestUtils.initDatabaseTable("stockAggregation_MINUTES");
            RDBMSTableTestUtils.initDatabaseTable("stockAggregation_HOURS");
            RDBMSTableTestUtils.initDatabaseTable("stockAggregation_DAYS");
            RDBMSTableTestUtils.initDatabaseTable("stockAggregation_MONTHS");
            RDBMSTableTestUtils.initDatabaseTable("stockAggregation_YEARS");
        } catch (SQLException e) {
            LOG.error("Test case ignored due to " + e.getMessage());
        }

    }

    @Test
    public void aggregationFilterTestCase1() throws InterruptedException {
        LOG.info("aggregationFilterTestCase1");
        SiddhiManager siddhiManager = new SiddhiManager();

        String stockStream =
                "define stream stockStream (symbol string, price float, lastClosingPrice float, volume long , " +
                        "quantity int, timestamp string);";
        String query = "" +
                "@Store(type=\"rdbms\", jdbc.url=\"" + url + "\", " +
                "username=\"" + user + "\", password=\"" + password + "\", jdbc.driver.name=\"" + driverClassName +
                "\", pool.properties=\"maximumPoolSize:2, maxLifetime:60000\")\n" +
                "@purge(enable='false')" +
                "define aggregation stockAggregation " +
                "from stockStream " +
                "select symbol, avg(price) as avgPrice, sum(price) as totalPrice, (price * quantity) " +
                "as lastTradeValue  " +
                "group by symbol " +
                "aggregate by timestamp every sec...year; " +

                "define stream inputStream (symbol string, value int, startTime string, " +
                "endTime string, perValue string); " +

                "@info(name = 'query1') " +
                "from inputStream as i join stockAggregation as s " +
                "on i.symbol == s.symbol " +
                "within \"2017-06-01 09:35:00 +05:30\", \"2017-06-01 10:37:57 +05:30\" " +
                "per i.perValue " +
                "select s.symbol, avgPrice, totalPrice as sumPrice, lastTradeValue  " +
                "insert all events into outputStream; ";

        SiddhiAppRuntime siddhiAppRuntime = siddhiManager.createSiddhiAppRuntime(stockStream + query);

        siddhiAppRuntime.addCallback("query1", new QueryCallback() {
            @Override
            public void receive(long timestamp, Event[] inEvents, Event[] removeEvents) {
                if (inEvents != null) {
                    EventPrinter.print(timestamp, inEvents, removeEvents);
                    for (Event event : inEvents) {
                        inEventsList.add(event.getData());
                        inEventCount.incrementAndGet();
                    }
                    eventArrived = true;
                }
                eventArrived = true;
            }
        });

        InputHandler stockStreamInputHandler = siddhiAppRuntime.getInputHandler("stockStream");
        InputHandler inputStreamInputHandler = siddhiAppRuntime.getInputHandler("inputStream");
        siddhiAppRuntime.start();

        // Thursday, June 1, 2017 4:05:50 AM (add 5.30 to get corresponding IST time)
        stockStreamInputHandler.send(new Object[]{"WSO2", 50f, 60f, 90L, 6, "2017-06-01 04:05:50"});

        // Thursday, June 1, 2017 4:05:51 AM
        stockStreamInputHandler.send(new Object[]{"IBM", 50f, 60f, 90L, 6, "2017-06-01 04:05:51"});

        // Thursday, June 1, 2017 4:05:52 AM
        stockStreamInputHandler.send(new Object[]{"WSO2", 60f, 44f, 200L, 56, "2017-06-01 04:05:52"});
        stockStreamInputHandler.send(new Object[]{"WSO2", 100f, null, 200L, 16, "2017-06-01 04:05:52"});

        // Thursday, June 1, 2017 4:05:50 AM (out of order. must be processed with 1st event for 50th second)
        stockStreamInputHandler.send(new Object[]{"WSO2", 70f, null, 40L, 10, "2017-06-01 04:05:50"});

        // Thursday, June 1, 2017 4:05:54 AM
        stockStreamInputHandler.send(new Object[]{"IBM", 100f, null, 200L, 26, "2017-06-01 04:05:54"});
        stockStreamInputHandler.send(new Object[]{"IBM", 100f, null, 200L, 96, "2017-06-01 04:05:54"});

        // Thursday, June 1, 2017 4:05:50 AM (out of order)
        stockStreamInputHandler.send(new Object[]{"IBM", 50f, 60f, 90L, 6, "2017-06-01 04:05:50"});

        // Thursday, June 1, 2017 4:05:56 AM
        stockStreamInputHandler.send(new Object[]{"IBM", 900f, null, 200L, 60, "2017-06-01 04:05:56"});
        stockStreamInputHandler.send(new Object[]{"IBM", 500f, null, 200L, 7, "2017-06-01 04:05:56"});

        // Thursday, June 1, 2017 4:06:56 AM
        stockStreamInputHandler.send(new Object[]{"IBM", 400f, null, 200L, 9, "2017-06-01 04:06:56"});

        // Thursday, June 1, 2017 4:07:56 AM
        stockStreamInputHandler.send(new Object[]{"IBM", 600f, null, 200L, 6, "2017-06-01 04:07:56"});

        // Thursday, June 1, 2017 5:07:56 AM
        stockStreamInputHandler.send(new Object[]{"IBM", 700f, null, 200L, 20, "2017-06-01 05:07:56"});

        Thread.sleep(1000);
        inputStreamInputHandler.send(new Object[]{"IBM", 1, "2017-06-01 09:35:51 +05:30",
                "2017-06-01 09:35:52 +05:30", "minutes"});
        Thread.sleep(1000);

        List<Object[]> expected = Arrays.asList(
                new Object[]{"IBM", 283.3333333333333, 1700.0, 3500f},
                new Object[]{"IBM", 400.0, 400.0, 3600f},
                new Object[]{"IBM", 700.0, 700.0, 14000f},
                new Object[]{"IBM", 600.0, 600.0, 3600f}
        );
        SiddhiTestHelper.waitForEvents(100, 4, inEventCount, 60000);
        AssertJUnit.assertTrue("Event arrived", eventArrived);
        AssertJUnit.assertEquals("Number of success events", 4, inEventCount.get());
        AssertJUnit.assertTrue("In events matched", SiddhiTestHelper.isUnsortedEventsMatch(inEventsList, expected));
        siddhiAppRuntime.shutdown();
        siddhiManager.shutdown();
    }


    @Test(dependsOnMethods = {"aggregationFilterTestCase1"})
    public void aggregationFilterTestCase2() throws InterruptedException {
        LOG.info("aggregationFilterTestCase2");
        SiddhiManager siddhiManager = new SiddhiManager();

        String stockStream =
                "define stream stockStream (symbol string, price float, lastClosingPrice float, volume long , " +
                        "quantity int, timestamp string);";
        String query = "" +
                "@Store(type=\"rdbms\", jdbc.url=\"" + url + "\", " +
                "username=\"" + user + "\", password=\"" + password + "\", jdbc.driver.name=\"" + driverClassName +
                "\")\n" +
                "@purge(enable='false')" +
                "define aggregation stockAggregation " +
                "from stockStream " +
                "select symbol, avg(price) as avgPrice, sum(price) as totalPrice, (price * quantity) " +
                "as lastTradeValue  " +
                "group by symbol " +
                "aggregate by timestamp every sec...year; " +

                "define stream inputStream (symbol string, value int, startTime string, " +
                "endTime string, perValue string); " +

                "@info(name = 'query1') " +
                "from inputStream as i join stockAggregation as s " +
                "on i.symbol == s.symbol " +
                "within \"2017-06-01 09:35:00 +05:30\", \"2017-06-01 10:37:57 +05:30\" " +
                "per \"minutes\" " +
                "select s.symbol, avgPrice, totalPrice as sumPrice, lastTradeValue, AGG_TIMESTAMP  " +
                "insert all events into outputStream; ";

        SiddhiAppRuntime siddhiAppRuntime = siddhiManager.createSiddhiAppRuntime(stockStream + query);

        siddhiAppRuntime.addCallback("query1", new QueryCallback() {
            @Override
            public void receive(long timestamp, Event[] inEvents, Event[] removeEvents) {
                if (inEvents != null) {
                    EventPrinter.print(timestamp, inEvents, removeEvents);
                    for (Event event : inEvents) {
                        inEventsList.add(event.getData());
                        inEventCount.incrementAndGet();
                    }
                    eventArrived = true;
                }
                eventArrived = true;
            }
        });

        InputHandler stockStreamInputHandler = siddhiAppRuntime.getInputHandler("stockStream");
        InputHandler inputStreamInputHandler = siddhiAppRuntime.getInputHandler("inputStream");
        siddhiAppRuntime.start();

        // Thursday, June 1, 2017 4:05:50 AM (add 5.30 to get corresponding IST time)
        stockStreamInputHandler.send(new Object[]{"WSO2", 50f, 60f, 90L, 6, "2017-06-01 04:05:50"});

        // Thursday, June 1, 2017 4:05:51 AM
        stockStreamInputHandler.send(new Object[]{"IBM", 50f, 60f, 90L, 6, "2017-06-01 04:05:51"});

        // Thursday, June 1, 2017 4:05:52 AM
        stockStreamInputHandler.send(new Object[]{"WSO2", 60f, 44f, 200L, 56, "2017-06-01 04:05:52"});
        stockStreamInputHandler.send(new Object[]{"WSO2", 100f, null, 200L, 16, "2017-06-01 04:05:52"});

        // Thursday, June 1, 2017 4:05:50 AM (out of order. must be processed with 1st event for 50th second)
        stockStreamInputHandler.send(new Object[]{"WSO2", 70f, null, 40L, 10, "2017-06-01 04:05:50"});

        // Thursday, June 1, 2017 4:05:54 AM
        stockStreamInputHandler.send(new Object[]{"IBM", 100f, null, 200L, 26, "2017-06-01 04:05:54"});
        stockStreamInputHandler.send(new Object[]{"IBM", 100f, null, 200L, 96, "2017-06-01 04:05:54"});

        // Thursday, June 1, 2017 4:05:50 AM (out of order and older than buffer. should be processed with the
        // current minimum event [which is 51st second] in the buffer since @IgnoreEventsOlderThanBuffer is not set.
        // IgnoreEventsOlderThanBuffer is false by default)
        stockStreamInputHandler.send(new Object[]{"IBM", 50f, 60f, 90L, 6, "2017-06-01 04:05:50"});

        // Thursday, June 1, 2017 4:05:56 AM
        stockStreamInputHandler.send(new Object[]{"IBM", 900f, null, 200L, 60, "2017-06-01 04:05:56"});
        stockStreamInputHandler.send(new Object[]{"IBM", 500f, null, 200L, 7, "2017-06-01 04:05:56"});

        // Thursday, June 1, 2017 4:06:56 AM
        stockStreamInputHandler.send(new Object[]{"IBM", 400f, null, 200L, 9, "2017-06-01 04:06:56"});

        // Thursday, June 1, 2017 4:07:56 AM
        stockStreamInputHandler.send(new Object[]{"IBM", 600f, null, 200L, 6, "2017-06-01 04:07:56"});

        // Thursday, June 1, 2017 5:07:56 AM
        stockStreamInputHandler.send(new Object[]{"IBM", 700f, null, 200L, 20, "2017-06-01 05:07:56"});

        Thread.sleep(100);
        inputStreamInputHandler.send(new Object[]{"IBM", 1, "2017-06-01 09:35:51 +05:30",
                "2017-06-01 09:35:52 +05:30", "minutes"});
        Thread.sleep(100);

        List<Object[]> expected = Arrays.asList(
                new Object[]{"IBM", 283.3333333333333, 1700.0, 3500f, 1496289900000L},
                new Object[]{"IBM", 400.0, 400.0, 3600f, 1496289960000L},
                new Object[]{"IBM", 700.0, 700.0, 14000f, 1496293620000L},
                new Object[]{"IBM", 600.0, 600.0, 3600f, 1496290020000L}
        );
        SiddhiTestHelper.waitForEvents(100, 4, inEventCount, 60000);
        AssertJUnit.assertTrue("Event arrived", eventArrived);
        AssertJUnit.assertEquals("Number of success events", 4, inEventCount.get());
        AssertJUnit.assertTrue("In events matched", SiddhiTestHelper.isUnsortedEventsMatch(inEventsList, expected));

        Thread.sleep(5000);

        siddhiAppRuntime.shutdown();
        siddhiManager.shutdown();
    }

    @Test(dependsOnMethods = {"aggregationFilterTestCase2"})
    public void aggregationFilterTestCase3() throws InterruptedException {
        LOG.info("aggregationFilterTestCase3");
        SiddhiManager siddhiManager = new SiddhiManager();

        String stockStream =
                "define stream stockStream (symbol string, price float, lastClosingPrice float, volume long , " +
                        "quantity int, timestamp long);";
        String query =
                "@Store(type=\"rdbms\", jdbc.url=\"" + url + "\", " +
                "username=\"" + user + "\", password=\"" + password + "\", jdbc.driver.name=\"" + driverClassName +
                "\", pool.properties=\"maximumPoolSize:2, maxLifetime:60000\")\n" +
                "@purge(enable='false')" +
                "define aggregation stockAggregation " +
                "from stockStream " +
                "select symbol, avg(price) as avgPrice, sum(price) as totalPrice, " +
                "(price * quantity) as lastTradeValue " +
                "group by symbol " +
                "aggregate by timestamp every sec...year; " +

                "define stream inputStream (symbol string, value int, startTime string, " +
                "endTime string, perValue string); " +

                "@info(name = 'query1') " +
                "from inputStream as i join stockAggregation as s " +
                "within \"2017-06-** **:**:**\" " +
                "per \"seconds\" " +
                "select AGG_TIMESTAMP, s.symbol, lastTradeValue, totalPrice " +
                "order by AGG_TIMESTAMP " +
                "insert all events into outputStream; ";

        SiddhiAppRuntime siddhiAppRuntime = siddhiManager.createSiddhiAppRuntime(stockStream + query);

        siddhiAppRuntime.addCallback("query1", new QueryCallback() {
            @Override
            public void receive(long timestamp, Event[] inEvents, Event[] removeEvents) {
                if (inEvents != null) {
                    EventPrinter.print(timestamp, inEvents, removeEvents);
                    for (Event event : inEvents) {
                        inEventsList.add(event.getData());
                        inEventCount.incrementAndGet();
                    }
                    eventArrived = true;
                }
                eventArrived = true;
            }
        });
        InputHandler stockStreamInputHandler = siddhiAppRuntime.getInputHandler("stockStream");
        InputHandler inputStreamInputHandler = siddhiAppRuntime.getInputHandler("inputStream");
        siddhiAppRuntime.start();

        // Thursday, June 1, 2017 4:05:50 AM
        stockStreamInputHandler.send(new Object[]{"WSO2", 50f, 60f, 90L, 6, 1496289950000L});
        stockStreamInputHandler.send(new Object[]{"WSO2", 70f, null, 40L, 10, 1496289950000L});

        // Thursday, June 1, 2017 4:05:52 AM
        stockStreamInputHandler.send(new Object[]{"WSO2", 60f, 44f, 200L, 56, 1496289952000L});
        stockStreamInputHandler.send(new Object[]{"WSO2", 100f, null, 200L, 16, 1496289952000L});

        // Thursday, June 1, 2017 4:05:50 AM (out of order. since there's no buffer, this must be processed with
        // 52nd second's data. since IgnoreEventsOlderThanBuffer is false by default, the event must not be dropped)
        stockStreamInputHandler.send(new Object[]{"WSO2", 50f, 60f, 90L, 6, 1496289950000L});
        stockStreamInputHandler.send(new Object[]{"WSO2", 70f, null, 40L, 10, 1496289950000L});

        // Thursday, June 1, 2017 4:05:54 AM
        stockStreamInputHandler.send(new Object[]{"IBM", 100f, null, 200L, 26, 1496289954000L});
        stockStreamInputHandler.send(new Object[]{"IBM", 100f, null, 200L, 96, 1496289954000L});

        // Thursday, June 1, 2017 4:05:56 AM
        stockStreamInputHandler.send(new Object[]{"IBM", 900f, null, 200L, 60, 1496289956000L});
        stockStreamInputHandler.send(new Object[]{"IBM", 500f, null, 200L, 7, 1496289956000L});

        // Thursday, June 1, 2017 4:06:56 AM
        stockStreamInputHandler.send(new Object[]{"IBM", 400f, null, 200L, 9, 1496290016000L});

        // Thursday, June 1, 2017 4:07:56 AM
        stockStreamInputHandler.send(new Object[]{"IBM", 600f, null, 200L, 6, 1496290076000L});

        // Thursday, June 1, 2017 5:07:56 AM
        stockStreamInputHandler.send(new Object[]{"CISCO", 700f, null, 200L, 20, 1496293676000L});

        // Thursday, June 1, 2017 6:07:56 AM
        stockStreamInputHandler.send(new Object[]{"WSO2", 60f, 44f, 200L, 56, 1496297276000L});

        // Friday, June 2, 2017 6:07:56 AM
        stockStreamInputHandler.send(new Object[]{"CISCO", 800f, null, 100L, 10, 1496383676000L});

        // Saturday, June 3, 2017 6:07:56 AM
        stockStreamInputHandler.send(new Object[]{"CISCO", 900f, null, 100L, 15, 1496470076000L});

        // Monday, July 3, 2017 6:07:56 AM
        stockStreamInputHandler.send(new Object[]{"IBM", 100f, null, 200L, 96, 1499062076000L});

        // Thursday, August 3, 2017 6:07:56 AM
        stockStreamInputHandler.send(new Object[]{"IBM", 400f, null, 200L, 9, 1501740476000L});

        // Friday, August 3, 2018 6:07:56 AM
        stockStreamInputHandler.send(new Object[]{"WSO2", 60f, 44f, 200L, 6, 1533276476000L});

        // Saturday, August 3, 2019 6:07:56 AM
        stockStreamInputHandler.send(new Object[]{"WSO2", 260f, 44f, 200L, 16, 1564812476000L});

        // Monday, August 3, 2020 6:07:56 AM
        stockStreamInputHandler.send(new Object[]{"CISCO", 260f, 44f, 200L, 16, 1596434876000L});

        // Monday, December 3, 2020 6:07:56 AM
        stockStreamInputHandler.send(new Object[]{"CISCO", 260f, 44f, 200L, 16, 1606975676000L});

        Thread.sleep(100);
        inputStreamInputHandler.send(new Object[]{"IBM", 1, "2017-06-01 09:35:51 +05:30",
                "2017-06-01 09:35:52 +05:30", "seconds"});
        Thread.sleep(100);

        List<Object[]> expected = Arrays.asList(
                new Object[]{1496289950000L, "WSO2", 700f, 240.0},
                new Object[]{1496289952000L, "WSO2", 1600f, 160.0},
                new Object[]{1496289954000L, "IBM", 9600f, 200.0},
                new Object[]{1496289956000L, "IBM", 3500f, 1400.0},
                new Object[]{1496290016000L, "IBM", 3600f, 400.0},
                new Object[]{1496290076000L, "IBM", 3600f, 600.0},
                new Object[]{1496293676000L, "CISCO", 14000f, 700.0},
                new Object[]{1496297276000L, "WSO2", 3360f, 60.0},
                new Object[]{1496383676000L, "CISCO", 8000f, 800.0},
                new Object[]{1496470076000L, "CISCO", 13500f, 900.0}
        );
        SiddhiTestHelper.waitForEvents(100, 10, inEventCount, 60000);
        AssertJUnit.assertTrue("In events matched", SiddhiTestHelper.isEventsMatch(inEventsList, expected));
        AssertJUnit.assertEquals("Number of success events", 10, inEventCount.get());
        AssertJUnit.assertTrue("Event arrived", eventArrived);

        Thread.sleep(1000);

        siddhiAppRuntime.shutdown();
        siddhiManager.shutdown();
    }

    @Test(dependsOnMethods = {"aggregationFilterTestCase3"})
    public void aggregationFilterTestCase4() throws InterruptedException {
        LOG.info("incrementalStreamProcessorTest11");
        SiddhiManager siddhiManager = new SiddhiManager();

        String stockStream =
                "define stream stockStream (symbol string, price float, lastClosingPrice float, volume long , " +
                        "quantity int, timestamp long);";
        String query = " " +
                "@Store(type=\"rdbms\", jdbc.url=\"" + url + "\", " +
                "username=\"" + user + "\", password=\"" + password + "\", jdbc.driver.name=\"" + driverClassName +
                "\", pool.properties=\"maximumPoolSize:2, maxLifetime:60000\")\n" +
                "@purge(enable='false')" +
                "define aggregation stockAggregation " +
                "from stockStream " +
                "select symbol, avg(price) as avgPrice, sum(price) as totalPrice, (price * quantity) " +
                "as lastTradeValue  " +
                "group by symbol " +
                "aggregate by timestamp every sec...year ;";

        SiddhiAppRuntime siddhiAppRuntime = siddhiManager.createSiddhiAppRuntime(stockStream + query);

        InputHandler stockStreamInputHandler = siddhiAppRuntime.getInputHandler("stockStream");
        siddhiAppRuntime.start();

        stockStreamInputHandler.send(new Object[]{"WSO2", 50f, 60f, 90L, 6, 1496289950000L});
        stockStreamInputHandler.send(new Object[]{"WSO2", 70f, null, 40L, 10, 1496289950000L});

        stockStreamInputHandler.send(new Object[]{"WSO2", 60f, 44f, 200L, 56, 1496289952000L});
        stockStreamInputHandler.send(new Object[]{"WSO2", 100f, null, 200L, 16, 1496289952000L});

        stockStreamInputHandler.send(new Object[]{"IBM", 100f, null, 200L, 26, 1496289954000L});
        stockStreamInputHandler.send(new Object[]{"IBM", 100f, null, 200L, 96, 1496289954000L});
        Thread.sleep(100);

        Event[] events = siddhiAppRuntime.query("from stockAggregation " +
                "on symbol==\"IBM\" " +
                "within \"2017-06-** **:**:**\" " +
                "per \"seconds\" " +
                "select symbol, avgPrice;");
        EventPrinter.print(events);
        AssertJUnit.assertNotNull(events);
        AssertJUnit.assertEquals(1, events.length);
        AssertJUnit.assertArrayEquals(new Object[]{"IBM", 100.0}, events[0].getData());

        Thread.sleep(100);
        siddhiAppRuntime.shutdown();
        siddhiManager.shutdown();
    }

    @Test(dependsOnMethods = {"aggregationFilterTestCase4"})
    public void aggregationFilterTestCase5() throws InterruptedException {
        LOG.info("AggregationFilterTestCase5");
        SiddhiManager siddhiManager = new SiddhiManager();
        String stockStream =
                "define stream stockStream (symbol string, price float, lastClosingPrice float, volume long , " +
                        "quantity int, timestamp long);";
        String query =
                "@Store(type=\"rdbms\", jdbc.url=\"" + url + "\", username=\"" + user + "\", password=\"" + password +
                        "\",jdbc.driver.name=\"" + driverClassName + "\")\n" +
                        "@purge(enable='false')" +
                        "define aggregation stockAggregation " +
                        "from stockStream " +
                        "select symbol, avg(price) as avgPrice, sum(price) as totalPrice, " +
                        "(price * quantity) as lastTradeValue  " +
                        "group by symbol " +
                        "aggregate every sec...year ;";

        SiddhiAppRuntime siddhiAppRuntime = siddhiManager.createSiddhiAppRuntime(stockStream + query);

        InputHandler stockStreamInputHandler = siddhiAppRuntime.getInputHandler("stockStream");
        siddhiAppRuntime.start();

        stockStreamInputHandler.send(new Object[]{"WSO2", 50f, 60f, 90L, 6, 1496289950000L});
        stockStreamInputHandler.send(new Object[]{"WSO2", 70f, null, 40L, 10, 1496289950000L});
        Thread.sleep(2000);

        stockStreamInputHandler.send(new Object[]{"WSO2", 60f, 44f, 200L, 56, 1496289952000L});
        stockStreamInputHandler.send(new Object[]{"WSO2", 100f, null, 200L, 16, 1496289952000L});
        Thread.sleep(2000);

        stockStreamInputHandler.send(new Object[]{"IBM", 100f, null, 200L, 26, 1496289954000L});
        stockStreamInputHandler.send(new Object[]{"IBM", 100f, null, 200L, 96, 1496289954000L});
        Thread.sleep(5000);

        LocalDate currentDate = LocalDate.now();
        String year = String.valueOf(currentDate.getYear());
        String month = String.valueOf(currentDate.getMonth().getValue());
        if (month.length() == 1) {
            month = "0".concat(month);
        }

        Event[] events = siddhiAppRuntime.query("from stockAggregation " +
                "on symbol == \"IBM\" " +
                "within \"" + year + "-" + month + "-** **:**:** +05:30\" " +
                "per \"seconds\"; ");

        EventPrinter.print(events);
        AssertJUnit.assertNotNull(events);
        AssertJUnit.assertEquals(1, events.length);

        Object[] copyEventsWithoutTime = new Object[4];
        System.arraycopy(events[0].getData(), 1, copyEventsWithoutTime, 0, 4);
        AssertJUnit.assertArrayEquals(new Object[]{"IBM", 100.0, 200.0, 9600f}, copyEventsWithoutTime);

        Thread.sleep(100);
        siddhiAppRuntime.shutdown();
    }

}
