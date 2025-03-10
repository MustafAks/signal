package com.binance.signal;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.DateTickUnit;
import org.jfree.chart.axis.DateTickUnitType;
import org.jfree.chart.plot.CombinedDomainXYPlot;
import org.jfree.chart.plot.ValueMarker;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.CandlestickRenderer;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.title.TextTitle;
import org.jfree.chart.ui.RectangleAnchor;
import org.jfree.chart.ui.TextAnchor;
import org.jfree.data.xy.DefaultHighLowDataset;
import org.jfree.data.xy.OHLCDataset;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.jfree.chart.annotations.XYPointerAnnotation;
import org.jfree.chart.ui.RectangleEdge;
import org.json.JSONArray;
import org.json.JSONException;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.apache.commons.math3.distribution.NormalDistribution;
import java.awt.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.Arrays;

@SpringBootApplication
public class SignalApplication {

    // Sembol ve API URL’lerinin yükleneceği dosya adı
    private static final String SYMBOLS_AND_API_URLS_FILE = "SymbolsAndApiUrl";
    private static final Map<String, String> SYMBOLS_AND_API_URLS = new HashMap<>();

    // Telegram için bot token ve chatId (konfigürasyon dosyasından veya environment’dan alınabilir)
    private static final String BOT_TOKEN = "6482508265:AAEDUmyCM-ygU7BVO-txyykS7cKn5URspmY";
    private static final long CHAT_ID = 1692398446;

    // Uygulama başlangıcında sembolleri ve API URL’lerini yükle
    static {
        loadSymbolsAndApiUrlsFromFile();
    }

    /**
     * Sembol ve API URL’lerini kaynak dosyasından okur.
     */
    private static void loadSymbolsAndApiUrlsFromFile() {
        Path path = Paths.get("src/main/resources/" + SYMBOLS_AND_API_URLS_FILE);
        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                String[] parts = line.split(",");
                if (parts.length == 2) {
                    SYMBOLS_AND_API_URLS.put(parts[0].trim(), parts[1].trim());
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("SymbolsAndApiUrl dosyası okunamadı!");
        }
    }

    /**
     * Ana trade simülasyon sonucu nesnesi.
     */
    private static class TradeResult {
        String tradeType;
        double entryPrice;
        double exitPrice;
        double profitPct;

        public TradeResult(String tradeType, double entryPrice, double exitPrice, double profitPct) {
            this.tradeType = tradeType;
            this.entryPrice = entryPrice;
            this.exitPrice = exitPrice;
            this.profitPct = profitPct;
        }
    }

    /**
     * Her gün için oluşturulan simülasyon kaydını tutan sınıf.
     */
    private static class SimulationRecord {
        String symbol;
        String tradeType;
        double predictedEntry;
        double predictedExit;
        double predictedProfitPct;
        double actualHigh;
        double actualLow;
        String outcome; // "Success" veya "Fail"
        Date simulationDate;

        @Override
        public String toString() {
            return String.format("Symbol: %s | %s | Entry: %.2f | Exit: %.2f | Predicted Profit: %.2f%% | Actual High: %.2f | Actual Low: %.2f | Outcome: %s | Date: %s",
                    symbol, tradeType, predictedEntry, predictedExit, predictedProfitPct, actualHigh, actualLow, outcome, simulationDate.toString());
        }
    }

    // En son 10 simülasyon kaydını tutan liste (FIFO)
    private static final List<SimulationRecord> simulationRecords = new ArrayList<SimulationRecord>();

    /**
     * Main metodunda, tüm iş akışını planlı olarak çalıştırıyoruz.
     */
    public static void main(String[] args) {
        // Veritabanı yöneticisini başlatma
        DatabaseManager dbManager = null;
        try {
            Class.forName("org.sqlite.JDBC");
            dbManager = new DatabaseManager();
        } catch (SQLException | ClassNotFoundException e) {
            e.printStackTrace();
            return;
        }

        // Görev zamanlayıcı: örneğin her 1 saatte bir çalışacak şekilde ayarlandı.
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        DatabaseManager finalDbManager = dbManager;
        Runnable task = () -> {
            try {
                Calendar cal = Calendar.getInstance();
                Date endDate = cal.getTime();
                cal.add(Calendar.MONTH, -6);
                Date startDate = cal.getTime();

                // Her sembol için
                for (Map.Entry<String, String> entry : SYMBOLS_AND_API_URLS.entrySet()) {
                    String symbol = entry.getKey();
                    String apiUrl = entry.getValue();

                    // API’den veri çekme
                    JSONArray klines = DataFetcher.fetchKlines(apiUrl);
                    if (klines == null) {
                        System.out.println("Veri çekilemedi: " + symbol);
                        continue;
                    }
                    int dataCount = klines.length();
                    double[] openPrices = new double[dataCount];
                    double[] highPrices = new double[dataCount];
                    double[] lowPrices = new double[dataCount];
                    double[] closePrices = new double[dataCount];
                    double[] volume = new double[dataCount];
                    List<Date> dates = new ArrayList<>();

                    // JSON verisini ayrıştırma
                    for (int i = 0; i < dataCount; i++) {
                        try {
                            JSONArray kline = klines.getJSONArray(i);
                            openPrices[i] = kline.getDouble(1);
                            highPrices[i] = kline.getDouble(2);
                            lowPrices[i] = kline.getDouble(3);
                            closePrices[i] = kline.getDouble(4);
                            volume[i] = kline.getDouble(5);
                            long timestamp = kline.getLong(0);
                            dates.add(new Date(timestamp));
                        } catch (JSONException je) {
                            je.printStackTrace();
                        }
                    }

                    // Yeni çekilen veriyi veritabanına kaydetme
                    try {
                        finalDbManager.storeKlineData(symbol, dates, openPrices, highPrices, lowPrices, closePrices, volume);
                    } catch (SQLException ex) {
                        ex.printStackTrace();
                    }

                    // DB’den son 6 aylık verileri çekip analiz etme
                    List<MarketDataRecord> historicalRecords = finalDbManager.getHistoricalData(symbol, startDate.getTime(), endDate.getTime());
                    if (historicalRecords.size() > 0) {
                        int recordSize = historicalRecords.size();
                        double[] histOpen = new double[recordSize];
                        double[] histHigh = new double[recordSize];
                        double[] histLow = new double[recordSize];
                        double[] histClose = new double[recordSize];
                        double[] histVolume = new double[recordSize];
                        List<Date> histDates = new ArrayList<>();
                        for (int i = 0; i < recordSize; i++) {
                            MarketDataRecord record = historicalRecords.get(i);
                            histOpen[i] = record.getOpen();
                            histHigh[i] = record.getHigh();
                            histLow[i] = record.getLow();
                            histClose[i] = record.getClose();
                            histVolume[i] = record.getVolume();
                            histDates.add(record.getTimestamp());
                        }

                        // Teknik göstergelerin hesaplanması
                        double[] movingAverages = TechnicalIndicators.calculateMovingAverage(histClose, 20);
                        double[] rsiValues = TechnicalIndicators.calculateRSI(histClose, 14);
                        double[][] macdValues = TechnicalIndicators.calculateMACD(histClose);
                        double[][] bollingerBands = TechnicalIndicators.calculateBollingerBands(histClose);
                        double[] adxValues = TechnicalIndicators.calculateADX(histHigh, histLow, histClose, 14);
                        double[] obvValues = TechnicalIndicators.calculateOBV(histClose, histVolume);
                        String candlePattern = TechnicalIndicators.analyzeCandlestickPatterns(histOpen, histClose, histHigh, histLow);

                        String weightedTrend = TechnicalIndicators.determineTrendDirection(histClose, movingAverages, rsiValues, macdValues);
                        String multiTrend = TechnicalIndicators.multiTimeframeAnalysis(histClose, movingAverages, rsiValues, macdValues, adxValues, obvValues);
                        String finalTrend = TechnicalIndicators.determineFinalTrend(weightedTrend, multiTrend, adxValues, candlePattern);

                        // Bugün için tahmin: geçmiş günlerdeki ortalama kazanç üzerinden bugünkü kapanış fiyatı ile exit tahmini
                        String todayPrediction = TradeSimulator.predictTodayTrade(historicalRecords, finalTrend);

                        // Bugünün simülasyon kaydını oluşturup kaydedelim
                        SimulationRecord simRec = TradeSimulator.recordTodaySimulation(historicalRecords, finalTrend);
                        if (simRec != null) {
                            simRec.symbol = symbol;
                            simulationRecords.add(simRec);
                            // Sadece en son 10 kaydı saklayalım
                            if (simulationRecords.size() > 10) {
                                simulationRecords.remove(0);
                            }
                        }

                        // Grafik oluşturma
                        File chartFile = ChartGenerator.generateTradingViewStyleChart(symbol, histDates, histOpen, histHigh, histLow, histClose, histVolume,
                                movingAverages, rsiValues, macdValues, bollingerBands, finalTrend);

                        // Telegram caption oluşturma
                        String caption = ChartGenerator.generateTwitterCaption(symbol, finalTrend)
                                + "\n" + todayPrediction;
                        if (caption.length() > 1024) {
                            caption = caption.substring(0, 1024);
                        }
                        TelegramNotifier.sendTelegramImage(BOT_TOKEN, CHAT_ID, chartFile, caption);

                        System.out.println(symbol + " için analiz tamamlandı. Final Trend: " + finalTrend);
                        System.out.println("Bugün İçin Tahmin: " + todayPrediction);
                    } else {
                        System.out.println(symbol + " için yeterli geçmiş veri bulunamadı.");
                    }
                }

                // Günün sonunda (örneğin her saatlik döngüde veya ayrı bir tetikleyici ile)
                // En son 10 simülasyon kaydının özetini yazdıralım:
                if (!simulationRecords.isEmpty()) {
                    System.out.println("----- Son 10 Simülasyon Kaydı -----");
                    for (SimulationRecord rec : simulationRecords) {
                        System.out.println(rec);
                    }
                    System.out.println("-------------------------------------");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        };

        // İlk çalıştırma hemen, ardından her 1 saatte bir
        scheduler.scheduleAtFixedRate(task, 0, 1, TimeUnit.HOURS);
    }

    // --- İç Sınıflar ve Yardımcı Metodlar ---

    /**
     * API’den Kline (mum) verisini çeken sınıf.
     */
    private static class DataFetcher {
        public static JSONArray fetchKlines(String apiUrl) {
            JSONArray klines = null;
            HttpURLConnection connection = null;
            try {
                URL url = new URL(apiUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    klines = new JSONArray(response.toString());
                }
            } catch (IOException | JSONException e) {
                e.printStackTrace();
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            return klines;
        }
    }

    /**
     * Teknik gösterge hesaplamaları için statik metotlar.
     */
    private static class TechnicalIndicators {

        public static double[] calculateMovingAverage(double[] prices, int period) {
            double[] movingAverages = new double[prices.length];
            for (int i = 0; i < prices.length; i++) {
                if (i < period - 1) {
                    movingAverages[i] = Double.NaN;
                } else {
                    double sum = 0;
                    for (int j = 0; j < period; j++) {
                        sum += prices[i - j];
                    }
                    movingAverages[i] = sum / period;
                }
            }
            return movingAverages;
        }

        public static double[] calculateRSI(double[] prices, int period) {
            double[] rsi = new double[prices.length];
            double gain = 0, loss = 0;
            // İlk period için toplayalım
            for (int i = 1; i < period; i++) {
                double change = prices[i] - prices[i - 1];
                if (change >= 0) {
                    gain += change;
                } else {
                    loss -= change;
                }
            }
            for (int i = period; i < prices.length; i++) {
                double change = prices[i] - prices[i - 1];
                if (change >= 0) {
                    gain = ((gain * (period - 1)) + change) / period;
                    loss = (loss * (period - 1)) / period;
                } else {
                    gain = (gain * (period - 1)) / period;
                    loss = ((loss * (period - 1)) - change) / period;
                }
                if (loss == 0) {
                    rsi[i] = 100;
                } else {
                    double rs = gain / loss;
                    rsi[i] = 100 - (100 / (1 + rs));
                }
            }
            for (int i = 0; i < period; i++) {
                rsi[i] = Double.NaN;
            }
            return rsi;
        }

        public static double[][] calculateMACD(double[] prices) {
            double[] ema12 = calculateEMA(prices, 12);
            double[] ema26 = calculateEMA(prices, 26);
            double[] macd = new double[prices.length];
            double[] signal = new double[prices.length];
            double[] histogram = new double[prices.length];
            for (int i = 0; i < prices.length; i++) {
                macd[i] = ema12[i] - ema26[i];
            }
            double[] ema9 = calculateEMA(macd, 9);
            for (int i = 0; i < prices.length; i++) {
                signal[i] = ema9[i];
                histogram[i] = macd[i] - signal[i];
            }
            return new double[][]{macd, signal, histogram};
        }

        public static double[] calculateEMA(double[] prices, int period) {
            double[] ema = new double[prices.length];
            double multiplier = 2.0 / (period + 1);
            ema[0] = prices[0];
            for (int i = 1; i < prices.length; i++) {
                ema[i] = ((prices[i] - ema[i - 1]) * multiplier) + ema[i - 1];
            }
            return ema;
        }

        public static double[][] calculateBollingerBands(double[] prices) {
            int period = 20;
            double[] sma = calculateSMA(prices, period);
            double[] upperBand = new double[prices.length];
            double[] lowerBand = new double[prices.length];
            double[] middleBand = sma;
            for (int i = period - 1; i < prices.length; i++) {
                double sum = 0;
                for (int j = 0; j < period; j++) {
                    sum += Math.pow(prices[i - j] - sma[i], 2);
                }
                double stddev = Math.sqrt(sum / period);
                upperBand[i] = sma[i] + (2 * stddev);
                lowerBand[i] = sma[i] - (2 * stddev);
            }
            return new double[][]{upperBand, middleBand, lowerBand};
        }

        public static double[] calculateSMA(double[] prices, int period) {
            double[] sma = new double[prices.length];
            for (int i = 0; i < prices.length; i++) {
                if (i < period - 1) {
                    sma[i] = Double.NaN;
                } else {
                    double sum = 0;
                    for (int j = 0; j < period; j++) {
                        sum += prices[i - j];
                    }
                    sma[i] = sum / period;
                }
            }
            return sma;
        }

        public static double[] calculateADX(double[] high, double[] low, double[] close, int period) {
            int n = high.length;
            double[] tr = new double[n];
            double[] plusDM = new double[n];
            double[] minusDM = new double[n];
            tr[0] = plusDM[0] = minusDM[0] = 0;
            for (int i = 1; i < n; i++) {
                double highDiff = high[i] - high[i - 1];
                double lowDiff = low[i - 1] - low[i];
                double d1 = high[i] - low[i];
                double d2 = Math.abs(high[i] - close[i - 1]);
                double d3 = Math.abs(low[i] - close[i - 1]);
                tr[i] = Math.max(d1, Math.max(d2, d3));
                plusDM[i] = (highDiff > lowDiff && highDiff > 0) ? highDiff : 0;
                minusDM[i] = (lowDiff > highDiff && lowDiff > 0) ? lowDiff : 0;
            }
            double[] atr = new double[n];
            double[] plusDI = new double[n];
            double[] minusDI = new double[n];
            double[] dx = new double[n];
            double[] adx = new double[n];
            double atrSum = 0, plusDMSum = 0, minusDMSum = 0;
            for (int i = 1; i <= period; i++) {
                atrSum += tr[i];
                plusDMSum += plusDM[i];
                minusDMSum += minusDM[i];
            }
            atr[period] = atrSum / period;
            plusDI[period] = (plusDMSum / atr[period]) * 100;
            minusDI[period] = (minusDMSum / atr[period]) * 100;
            dx[period] = Math.abs(plusDI[period] - minusDI[period]) / (plusDI[period] + minusDI[period]) * 100;
            for (int i = period + 1; i < n; i++) {
                atr[i] = ((atr[i - 1] * (period - 1)) + tr[i]) / period;
                plusDMSum = ((plusDMSum * (period - 1)) + plusDM[i]) / period;
                minusDMSum = ((minusDMSum * (period - 1)) + minusDM[i]) / period;
                plusDI[i] = (plusDMSum / atr[i]) * 100;
                minusDI[i] = (minusDMSum / atr[i]) * 100;
                dx[i] = Math.abs(plusDI[i] - minusDI[i]) / (plusDI[i] + minusDI[i]) * 100;
            }
            double dxSum = 0;
            for (int i = period; i < period * 2; i++) {
                dxSum += dx[i];
            }
            adx[period * 2 - 1] = dxSum / period;
            for (int i = period * 2; i < n; i++) {
                adx[i] = ((adx[i - 1] * (period - 1)) + dx[i]) / period;
            }
            return adx;
        }

        public static double[] calculateOBV(double[] closePrices, double[] volume) {
            int n = closePrices.length;
            double[] obv = new double[n];
            obv[0] = 0;
            for (int i = 1; i < n; i++) {
                if (closePrices[i] > closePrices[i - 1]) {
                    obv[i] = obv[i - 1] + volume[i];
                } else if (closePrices[i] < closePrices[i - 1]) {
                    obv[i] = obv[i - 1] - volume[i];
                } else {
                    obv[i] = obv[i - 1];
                }
            }
            return obv;
        }

        public static String analyzeCandlestickPatterns(double[] openPrices, double[] closePrices, double[] highPrices, double[] lowPrices) {
            int n = openPrices.length;
            if (n < 2) return "None";
            double openPrev = openPrices[n - 2], closePrev = closePrices[n - 2];
            double openLast = openPrices[n - 1], closeLast = closePrices[n - 1];
            if (closePrev < openPrev && closeLast > openLast
                    && openLast < closePrev * 1.01 && closeLast > openPrev * 0.99) {
                return "Bullish Engulfing";
            }
            if (closePrev > openPrev && closeLast < openLast
                    && openLast > closePrev * 0.99 && closeLast < openPrev * 1.01) {
                return "Bearish Engulfing";
            }
            double body = Math.abs(closeLast - openLast);
            double range = highPrices[n - 1] - lowPrices[n - 1];
            if (range > 0 && (body / range) < 0.15) {
                return "Doji";
            }
            double lowerShadow = Math.min(openLast, closeLast) - lowPrices[n - 1];
            double upperShadow = highPrices[n - 1] - Math.max(openLast, closeLast);
            if (body > 0 && lowerShadow >= 2 * body && upperShadow < body) {
                return "Hammer";
            }
            return "None";
        }

        public static String multiTimeframeAnalysis(double[] closePrices, double[] movingAverages, double[] rsiValues,
                                                    double[][] macdValues, double[] adxValues, double[] obvValues) {
            int len = closePrices.length;
            int shortTermPeriod = Math.min(20, len);
            double[] closeShort = Arrays.copyOfRange(closePrices, len - shortTermPeriod, len);
            double[] maShort = Arrays.copyOfRange(movingAverages, len - shortTermPeriod, len);
            double[] rsiShort = Arrays.copyOfRange(rsiValues, len - shortTermPeriod, len);
            double[][] macdShort = new double[2][shortTermPeriod];
            macdShort[0] = Arrays.copyOfRange(macdValues[0], len - shortTermPeriod, len);
            macdShort[1] = Arrays.copyOfRange(macdValues[1], len - shortTermPeriod, len);
            String shortTrend = determineTrendDirection(closeShort, maShort, rsiShort, macdShort);
            String longTrend = determineTrendDirection(closePrices, movingAverages, rsiValues, macdValues);
            return shortTrend.equals(longTrend) ? shortTrend : "Mixed";
        }

        public static String determineTrendDirection(double[] closingPrices, double[] movingAverages, double[] rsiValues,
                                                     double[][] macdValues) {
            double totalBullishScore = 0.0;
            double totalBearishScore = 0.0;
            double lastPrice = closingPrices[closingPrices.length - 1];
            double lastMA = movingAverages[movingAverages.length - 1];
            if (!Double.isNaN(lastMA) && lastMA != 0) {
                double maDiff = (lastPrice - lastMA) / lastMA;
                if (maDiff > 0) {
                    totalBullishScore += maDiff;
                } else {
                    totalBearishScore += -maDiff;
                }
            }
            double lastRSI = rsiValues[rsiValues.length - 1];
            if (!Double.isNaN(lastRSI)) {
                double rsiDiff = lastRSI - 50;
                if (rsiDiff > 0) {
                    totalBullishScore += rsiDiff / 50;
                } else {
                    totalBearishScore += -rsiDiff / 50;
                }
            }
            double lastMACD = macdValues[0][macdValues[0].length - 1];
            double lastSignal = macdValues[1][macdValues[1].length - 1];
            double macdDiff = lastMACD - lastSignal;
            double macdWeight = Math.tanh(macdDiff);
            if (macdWeight > 0) {
                totalBullishScore += macdWeight;
            } else {
                totalBearishScore += -macdWeight;
            }
            if (totalBullishScore > totalBearishScore) {
                return "Bullish";
            } else if (totalBearishScore > totalBullishScore) {
                return "Bearish";
            } else {
                return "Neutral";
            }
        }

        public static String determineFinalTrend(String weightedTrend, String multiTrend, double[] adxValues, String candlePattern) {
            double lastADX = adxValues[adxValues.length - 1];
            boolean strongTrend = lastADX > 25;
            if (candlePattern.equals("Bullish Engulfing") && (weightedTrend.equals("Bullish") || multiTrend.equals("Bullish"))) {
                return "Bullish";
            } else if (candlePattern.equals("Bearish Engulfing") && (weightedTrend.equals("Bearish") || multiTrend.equals("Bearish"))) {
                return "Bearish";
            }
            return weightedTrend.equals(multiTrend) ? weightedTrend : (strongTrend ? weightedTrend : "Mixed");
        }
    }

    /**
     * Trade simülasyonları için metotlar.
     *
     * Aşağıda, yalnızca geçmiş verilerin ortalamasını kullanmak yerine,
     * (1) basit istatistiksel regresyon (SimpleRegression) ve
     * (2) Monte Carlo simülasyonu ile tahmin yapacak ek metotlar eklenmiştir.
     */
    private static class TradeSimulator {

        /**
         * Belirli bir gün için en iyi trade sonuçlarını simüle eder.
         */
        public static TradeResult simulateOptimalTradeForDay(List<MarketDataRecord> dayRecords, String finalTrend) {
            if (dayRecords.size() < 2) return null;
            double bestProfit = -Double.MAX_VALUE;
            double bestEntry = 0;
            double bestExit = 0;
            String tradeType = "";
            if ("Bullish".equalsIgnoreCase(finalTrend)) {
                // Long işlemi: en düşük low’dan başlayıp sonraki en yüksek high aranıyor.
                for (int i = 0; i < dayRecords.size() - 1; i++) {
                    double entry = dayRecords.get(i).getLow();
                    for (int j = i + 1; j < dayRecords.size(); j++) {
                        double exit = dayRecords.get(j).getHigh();
                        double profit = (exit - entry) / entry;
                        if (profit > bestProfit) {
                            bestProfit = profit;
                            bestEntry = entry;
                            bestExit = exit;
                            tradeType = "Long (Alım)";
                        }
                    }
                }
            } else if ("Bearish".equalsIgnoreCase(finalTrend)) {
                // Short işlemi: en yüksek high’dan başlayıp sonraki en düşük low aranıyor.
                for (int i = 0; i < dayRecords.size() - 1; i++) {
                    double entry = dayRecords.get(i).getHigh();
                    for (int j = i + 1; j < dayRecords.size(); j++) {
                        double exit = dayRecords.get(j).getLow();
                        double profit = (entry - exit) / entry;
                        if (profit > bestProfit) {
                            bestProfit = profit;
                            bestEntry = entry;
                            bestExit = exit;
                            tradeType = "Short (Satım)";
                        }
                    }
                }
            } else {
                return null;
            }
            return new TradeResult(tradeType, bestEntry, bestExit, bestProfit * 100);
        }

        /**
         * Basit regresyon kullanarak geçmiş günlerin optimal trade kar yüzdesi verileri üzerinden,
         * gelecek gün için bir "profit" tahmini yapar.
         */
        public static double predictProfitUsingRegression(List<Double> profitPercentages) {
            SimpleRegression regression = new SimpleRegression();
            int n = profitPercentages.size();
            for (int i = 0; i < n; i++) {
                // Bağımsız değişken olarak gün indeksini, bağımlı değişken olarak profit yüzdesini kullanıyoruz.
                regression.addData(i, profitPercentages.get(i));
            }
            // Gelecek günün (n'inci gün) tahmini
            return regression.predict(n);
        }

        /**
         * Monte Carlo simülasyonu ile geçmiş verilerin dağılımını (ortalama ve standart sapma) kullanarak,
         * bugünkü kapanış fiyatı üzerinden çıkış fiyatı tahminlerini üretir.
         */
        public static String monteCarloSimulation(double currentPrice, List<Double> profitPercentages, int iterations) {
            int n = profitPercentages.size();
            if(n == 0) return "Yeterli veriye ulaşılamadı.";
            double sum = 0;
            for(double p : profitPercentages) {
                sum += p;
            }
            double mean = sum / n;
            double variance = 0;
            for(double p : profitPercentages) {
                variance += (p - mean) * (p - mean);
            }
            variance /= n;
            double stdDev = Math.sqrt(variance);
            NormalDistribution distribution = new NormalDistribution(mean, stdDev);
            double[] simulatedExitPrices = new double[iterations];
            for (int i = 0; i < iterations; i++) {
                double randomProfit = distribution.sample();
                // Bullish için: çıkış fiyatı = currentPrice * (1 + randomProfit/100)
                simulatedExitPrices[i] = currentPrice * (1 + randomProfit/100);
            }
            Arrays.sort(simulatedExitPrices);
            double median = simulatedExitPrices[iterations/2];
            double lowerBound = simulatedExitPrices[(int)(iterations*0.05)];
            double upperBound = simulatedExitPrices[(int)(iterations*0.95)];
            return String.format("Monte Carlo Simülasyonu (n=%d): Medyan Exit Fiyatı: %.2f, %d%% - %d%% aralığı: [%.2f, %.2f]",
                    iterations, median, 5, 95, lowerBound, upperBound);
        }

        /**
         * Bugün için tahmin oluşturur:
         * - DB’deki 6 aylık veriden, bugünkü gün hariç her gün için optimal trade kar yüzdesi hesaplanır.
         * - Bu kar yüzdelerinin ortalaması alınır.
         * - Basit regresyon ve Monte Carlo simülasyonu eklenir.
         * - Bugünün son kapanış fiyatı üzerinden (giriş), beklenen exit fiyatı hesaplanır.
         */
        public static String predictTodayTrade(List<MarketDataRecord> records, String finalTrend) {
            SimulationRecord simRec = recordTodaySimulation(records, finalTrend);
            if (simRec == null) return "Bugün için tahmin yapılamadı.";

            // Geçmiş günlerden (bugün hariç) optimal trade kar yüzdelerini hesaplayalım.
            Map<String, List<MarketDataRecord>> dailyRecords = new TreeMap<>();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
            for (MarketDataRecord record : records) {
                String dayKey = sdf.format(record.getTimestamp());
                dailyRecords.computeIfAbsent(dayKey, k -> new ArrayList<>()).add(record);
            }
            List<Double> profitPercentages = new ArrayList<>();
            String todayKey = "";
            if (!dailyRecords.isEmpty()) {
                List<String> sortedDays = new ArrayList<>(dailyRecords.keySet());
                todayKey = sortedDays.get(sortedDays.size() - 1);
            }
            for (Map.Entry<String, List<MarketDataRecord>> entry : dailyRecords.entrySet()) {
                if (entry.getKey().equals(todayKey)) continue;
                TradeResult tr = simulateOptimalTradeForDay(entry.getValue(), finalTrend);
                if (tr != null) {
                    profitPercentages.add(tr.profitPct);
                }
            }
            double predictedProfitRegression = predictProfitUsingRegression(profitPercentages);
            String monteCarloResult = monteCarloSimulation(simRec.predictedEntry, profitPercentages, 1000);
            return String.format("Bugün Tahmini: %s | Giriş: %.2f | Çıkış: %.2f | Beklenen Ortalama Kar: %.2f%%\nRegression Tahmini: %.2f%%\n%s",
                    simRec.tradeType, simRec.predictedEntry, simRec.predictedExit, simRec.predictedProfitPct, predictedProfitRegression, monteCarloResult);
        }

        /**
         * Bugün için simülasyon kaydını oluşturur.
         * Verilen 6 aylık veriden bugünü belirler; bugünkü son kapanış fiyatını giriş olarak alır,
         * geçmiş günlerden elde edilen optimal trade kar yüzdelerinin ortalaması ile exit tahmini yapar,
         * ardından bugünkü en yüksek/en düşük değerlerle karşılaştırarak işlemin başarılı olup olmayacağını belirler.
         */
        public static SimulationRecord recordTodaySimulation(List<MarketDataRecord> records, String finalTrend) {
            Map<String, List<MarketDataRecord>> dailyRecords = new TreeMap<>();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
            for (MarketDataRecord record : records) {
                String dayKey = sdf.format(record.getTimestamp());
                dailyRecords.computeIfAbsent(dayKey, k -> new ArrayList<>()).add(record);
            }
            List<String> sortedDays = new ArrayList<>(dailyRecords.keySet());
            if (sortedDays.isEmpty()) return null;
            // Bugün: en son gün
            String todayKey = sortedDays.get(sortedDays.size() - 1);
            List<MarketDataRecord> todayRecords = dailyRecords.get(todayKey);
            if (todayRecords == null || todayRecords.size() < 2) return null;
            // Bugünün son kapanış fiyatı giriş olarak alınır.
            double currentPrice = todayRecords.get(todayRecords.size() - 1).getClose();
            // Geçmiş günlerde (bugün hariç) benzer trend için optimal trade kar yüzdelerini hesaplayalım.
            List<Double> profitPercentages = new ArrayList<>();
            for (Map.Entry<String, List<MarketDataRecord>> entry : dailyRecords.entrySet()) {
                if (entry.getKey().equals(todayKey)) continue;
                TradeResult tr = simulateOptimalTradeForDay(entry.getValue(), finalTrend);
                if (tr != null) {
                    profitPercentages.add(tr.profitPct);
                }
            }
            double avgProfitPct = profitPercentages.stream().mapToDouble(d -> d).average().orElse(0);
            double predictedExitPrice;
            String tradeType;
            if ("Bullish".equalsIgnoreCase(finalTrend)) {
                tradeType = "Long (Alım)";
                predictedExitPrice = currentPrice * (1 + avgProfitPct / 100);
            } else if ("Bearish".equalsIgnoreCase(finalTrend)) {
                tradeType = "Short (Satım)";
                predictedExitPrice = currentPrice * (1 - avgProfitPct / 100);
            } else {
                return null;
            }
            // Bugünün en yüksek ve en düşük değerlerini bulalım.
            double actualHigh = todayRecords.stream().mapToDouble(r -> r.getHigh()).max().orElse(currentPrice);
            double actualLow = todayRecords.stream().mapToDouble(r -> r.getLow()).min().orElse(currentPrice);
            String outcome;
            if ("Long (Alım)".equals(tradeType)) {
                outcome = actualHigh >= predictedExitPrice ? "Success" : "Fail";
            } else if ("Short (Satım)".equals(tradeType)) {
                outcome = actualLow <= predictedExitPrice ? "Success" : "Fail";
            } else {
                outcome = "N/A";
            }
            SimulationRecord simRec = new SimulationRecord();
            simRec.tradeType = tradeType;
            simRec.predictedEntry = currentPrice;
            simRec.predictedExit = predictedExitPrice;
            simRec.predictedProfitPct = avgProfitPct;
            simRec.actualHigh = actualHigh;
            simRec.actualLow = actualLow;
            simRec.outcome = outcome;
            simRec.simulationDate = new Date();
            return simRec;
        }

        /**
         * Son 10 günün günlük trade simülasyon raporunu oluşturur.
         */
        public static String simulateDailyTrades(List<MarketDataRecord> records, String finalTrend) {
            Map<String, List<MarketDataRecord>> dailyRecords = new TreeMap<>();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
            for (MarketDataRecord record : records) {
                String dayKey = sdf.format(record.getTimestamp());
                dailyRecords.computeIfAbsent(dayKey, k -> new ArrayList<>()).add(record);
            }
            List<String> sortedDays = new ArrayList<>(dailyRecords.keySet());
            int totalDays = sortedDays.size();
            List<String> last10Days = sortedDays.subList(Math.max(totalDays - 10, 0), totalDays);

            StringBuilder report = new StringBuilder();
            report.append("Günlük İşlem Raporu (Son 10 Gün):\n");

            for (String dayKey : last10Days) {
                List<MarketDataRecord> dayRecords = dailyRecords.get(dayKey);
                if (dayRecords.size() < 2) {
                    report.append(dayKey).append(": Yeterli veri yok, işlem açılmadı.\n");
                    continue;
                }
                double entryPrice = 0;
                double exitPrice = 0;
                double bestProfit = -Double.MAX_VALUE;
                String tradeType = "";

                if ("Bullish".equalsIgnoreCase(finalTrend)) {
                    for (int i = 0; i < dayRecords.size() - 1; i++) {
                        double currentEntry = dayRecords.get(i).getLow();
                        for (int j = i + 1; j < dayRecords.size(); j++) {
                            double potentialExit = dayRecords.get(j).getHigh();
                            double profit = (potentialExit - currentEntry) / currentEntry;
                            if (profit > bestProfit) {
                                bestProfit = profit;
                                entryPrice = currentEntry;
                                exitPrice = potentialExit;
                                tradeType = "Long (Alım)";
                            }
                        }
                    }
                } else if ("Bearish".equalsIgnoreCase(finalTrend)) {
                    for (int i = 0; i < dayRecords.size() - 1; i++) {
                        double currentEntry = dayRecords.get(i).getHigh();
                        for (int j = i + 1; j < dayRecords.size(); j++) {
                            double potentialExit = dayRecords.get(j).getLow();
                            double profit = (currentEntry - potentialExit) / currentEntry;
                            if (profit > bestProfit) {
                                bestProfit = profit;
                                entryPrice = currentEntry;
                                exitPrice = potentialExit;
                                tradeType = "Short (Satım)";
                            }
                        }
                    }
                } else {
                    report.append(dayKey).append(": Nötr veya Mixed trend nedeniyle işlem açılmadı.\n");
                    continue;
                }

                if (entryPrice == 0 || exitPrice == 0) {
                    report.append(dayKey).append(": Geçersiz fiyat verisi nedeniyle işlem açılmadı.\n");
                } else {
                    double profitLossPct = bestProfit * 100;
                    report.append(String.format("%s: %s | Giriş: %.2f | Çıkış: %.2f | Kar/Zarar: %.2f%%\n",
                            dayKey, tradeType, entryPrice, exitPrice, profitLossPct));
                }
            }
            return report.toString();
        }
    }

    /**
     * Grafik oluşturma ve ilgili eklemeler.
     */
    private static class ChartGenerator {

        public static File generateTradingViewStyleChart(String symbol, List<Date> dates,
                                                         double[] openPrices, double[] highPrices,
                                                         double[] lowPrices, double[] closePrices,
                                                         double[] volume, double[] movingAverages,
                                                         double[] rsiValues, double[][] macdValues,
                                                         double[][] bollingerBands, String trendDirection) {
            OHLCDataset dataset = createDataset(symbol, dates, openPrices, highPrices, lowPrices, closePrices, volume);
            JFreeChart candlestickChart = ChartFactory.createCandlestickChart(
                    symbol + " Closing Prices",
                    "Time",
                    "Price",
                    dataset,
                    false
            );
            candlestickChart.setAntiAlias(true);
            XYPlot candlestickPlot = (XYPlot) candlestickChart.getPlot();
            candlestickPlot.setBackgroundPaint(Color.WHITE);
            candlestickPlot.setDomainGridlinePaint(Color.LIGHT_GRAY);
            candlestickPlot.setRangeGridlinePaint(Color.LIGHT_GRAY);
            candlestickPlot.setOutlineVisible(false);
            CandlestickRenderer candlestickRenderer = new CandlestickRenderer();
            candlestickRenderer.setUpPaint(new Color(34, 177, 76));
            candlestickRenderer.setDownPaint(new Color(237, 28, 36));
            candlestickPlot.setRenderer(candlestickRenderer);
            NumberAxis priceAxis = (NumberAxis) candlestickPlot.getRangeAxis();
            priceAxis.setAutoRangeIncludesZero(false);
            priceAxis.setLabelFont(new Font("Dialog", Font.BOLD, 14));
            priceAxis.setTickLabelFont(new Font("Dialog", Font.PLAIN, 12));
            priceAxis.setTickLabelPaint(Color.BLACK);

            // Moving Average serisi ekleme
            XYSeries maSeries = new XYSeries("20-Day MA");
            for (int i = 0; i < movingAverages.length; i++) {
                if (!Double.isNaN(movingAverages[i])) {
                    maSeries.add(dates.get(i).getTime(), movingAverages[i]);
                }
            }
            XYSeriesCollection maDataset = new XYSeriesCollection(maSeries);
            candlestickPlot.setDataset(1, maDataset);
            candlestickPlot.mapDatasetToRangeAxis(1, 0);
            XYLineAndShapeRenderer maRenderer = new XYLineAndShapeRenderer(true, false);
            maRenderer.setSeriesPaint(0, Color.BLUE);
            maRenderer.setSeriesStroke(0, new BasicStroke(2.0f));
            candlestickPlot.setRenderer(1, maRenderer);

            // Bollinger Bands ekleme
            XYSeries upperBandSeries = new XYSeries("Upper Band");
            XYSeries middleBandSeries = new XYSeries("Middle Band");
            XYSeries lowerBandSeries = new XYSeries("Lower Band");
            for (int i = 0; i < bollingerBands[0].length; i++) {
                if (!Double.isNaN(bollingerBands[0][i]) && !Double.isNaN(bollingerBands[1][i]) && !Double.isNaN(bollingerBands[2][i])) {
                    upperBandSeries.add(dates.get(i).getTime(), bollingerBands[0][i]);
                    middleBandSeries.add(dates.get(i).getTime(), bollingerBands[1][i]);
                    lowerBandSeries.add(dates.get(i).getTime(), bollingerBands[2][i]);
                }
            }
            XYSeriesCollection bollingerDataset = new XYSeriesCollection();
            bollingerDataset.addSeries(upperBandSeries);
            bollingerDataset.addSeries(middleBandSeries);
            bollingerDataset.addSeries(lowerBandSeries);
            candlestickPlot.setDataset(2, bollingerDataset);
            candlestickPlot.mapDatasetToRangeAxis(2, 0);
            XYLineAndShapeRenderer bollingerRenderer = new XYLineAndShapeRenderer(true, false);
            bollingerRenderer.setSeriesPaint(0, Color.BLACK);
            bollingerRenderer.setSeriesStroke(0, new BasicStroke(1.5f));
            bollingerRenderer.setSeriesPaint(1, Color.GRAY);
            bollingerRenderer.setSeriesStroke(1, new BasicStroke(1.5f));
            bollingerRenderer.setSeriesPaint(2, Color.DARK_GRAY);
            bollingerRenderer.setSeriesStroke(2, new BasicStroke(1.5f));
            candlestickPlot.setRenderer(2, bollingerRenderer);

            // Tarih ekseni düzenleme
            DateAxis dateAxis = new DateAxis("Time");
            dateAxis.setDateFormatOverride(new SimpleDateFormat("MMM-yyyy"));
            dateAxis.setTickUnit(new DateTickUnit(DateTickUnitType.MONTH, 1));
            dateAxis.setVerticalTickLabels(false);
            dateAxis.setTickLabelFont(new Font("Dialog", Font.PLAIN, 10));
            candlestickPlot.setDomainAxis(dateAxis);

            // Ek açıklamalar: MACD crossover gibi
            addHistoricalAnnotations(candlestickPlot, dates, macdValues, rsiValues);

            // Fibonacci seviyeleri ekleme
            double high = Arrays.stream(highPrices).max().orElse(Double.NaN);
            double low = Arrays.stream(lowPrices).min().orElse(Double.NaN);
            addFibonacciLevels(candlestickPlot, high, low, dates);

            // Son fiyat açıklaması
            addLastPriceAnnotation(candlestickPlot, dates.get(dates.size() - 1), closePrices[closePrices.length - 1]);

            // RSI Paneli
            XYPlot rsiPlot = new XYPlot();
            rsiPlot.setBackgroundPaint(Color.WHITE);
            rsiPlot.setDomainGridlinePaint(Color.LIGHT_GRAY);
            rsiPlot.setRangeGridlinePaint(Color.LIGHT_GRAY);
            NumberAxis rsiAxis = new NumberAxis("RSI");
            rsiAxis.setRange(0, 100);
            rsiPlot.setRangeAxis(rsiAxis);
            XYSeries rsiSeries = new XYSeries("RSI");
            for (int i = 0; i < rsiValues.length; i++) {
                if (!Double.isNaN(rsiValues[i])) {
                    rsiSeries.add(dates.get(i).getTime(), rsiValues[i]);
                }
            }
            XYSeriesCollection rsiDataset = new XYSeriesCollection(rsiSeries);
            rsiPlot.setDataset(rsiDataset);
            XYLineAndShapeRenderer rsiRenderer = new XYLineAndShapeRenderer(true, false);
            rsiRenderer.setSeriesPaint(0, Color.ORANGE);
            rsiRenderer.setSeriesStroke(0, new BasicStroke(2.0f));
            rsiPlot.setRenderer(rsiRenderer);
            addRSIThresholdLines(rsiPlot, 30, 70);

            // MACD Paneli
            XYPlot macdPlot = new XYPlot();
            macdPlot.setBackgroundPaint(Color.WHITE);
            macdPlot.setDomainGridlinePaint(Color.LIGHT_GRAY);
            macdPlot.setRangeGridlinePaint(Color.LIGHT_GRAY);
            NumberAxis macdAxis = new NumberAxis("MACD");
            macdPlot.setRangeAxis(macdAxis);
            XYSeries macdSeries = new XYSeries("MACD");
            XYSeries signalSeries = new XYSeries("Signal");
            XYSeries histogramSeries = new XYSeries("Histogram");
            for (int i = 0; i < macdValues[0].length; i++) {
                if (!Double.isNaN(macdValues[0][i]) && !Double.isNaN(macdValues[1][i]) && !Double.isNaN(macdValues[2][i])) {
                    macdSeries.add(dates.get(i).getTime(), macdValues[0][i]);
                    signalSeries.add(dates.get(i).getTime(), macdValues[1][i]);
                    histogramSeries.add(dates.get(i).getTime(), macdValues[2][i]);
                }
            }
            XYSeriesCollection macdDataset = new XYSeriesCollection();
            macdDataset.addSeries(macdSeries);
            macdDataset.addSeries(signalSeries);
            macdDataset.addSeries(histogramSeries);
            macdPlot.setDataset(macdDataset);
            XYLineAndShapeRenderer macdRenderer = new XYLineAndShapeRenderer(true, false);
            macdRenderer.setSeriesPaint(0, Color.MAGENTA);
            macdRenderer.setSeriesStroke(0, new BasicStroke(2.0f));
            macdRenderer.setSeriesPaint(1, Color.YELLOW);
            macdRenderer.setSeriesStroke(1, new BasicStroke(2.0f));
            macdRenderer.setSeriesPaint(2, Color.CYAN);
            macdRenderer.setSeriesStroke(2, new BasicStroke(2.0f));
            macdPlot.setRenderer(macdRenderer);
            addMACDZeroLine(macdPlot);

            // Kombine edilmiş grafik
            CombinedDomainXYPlot combinedPlot = new CombinedDomainXYPlot(dateAxis);
            combinedPlot.setGap(10.0);
            combinedPlot.add(candlestickPlot, 3);
            combinedPlot.add(rsiPlot, 1);
            combinedPlot.add(macdPlot, 1);
            JFreeChart combinedChart = new JFreeChart(
                    symbol + " Closing Prices",
                    JFreeChart.DEFAULT_TITLE_FONT,
                    combinedPlot,
                    true
            );
            Font font = new Font("Dialog", Font.PLAIN, 14);
            combinedPlot.getDomainAxis().setTickLabelFont(font);
            candlestickPlot.getRangeAxis().setTickLabelFont(font);
            rsiPlot.getRangeAxis().setTickLabelFont(font);
            macdPlot.getRangeAxis().setTickLabelFont(font);
            String futureNotes = generateFutureNotes(
                    trendDirection, rsiValues, macdValues,
                    TechnicalIndicators.calculateADX(highPrices, lowPrices, closePrices, 14),
                    TechnicalIndicators.analyzeCandlestickPatterns(openPrices, closePrices, highPrices, lowPrices)
            );
            addFutureNotes(combinedChart, futureNotes);
            addSymbolLegend(combinedChart);
            TextTitle trendTitle = new TextTitle("Trend Direction: " + trendDirection, new Font("Dialog", Font.BOLD, 14));
            trendTitle.setPosition(RectangleEdge.TOP);
            combinedChart.addSubtitle(trendTitle);
            File imageFile = new File("trading_view_style_chart_" + symbol + ".png");
            try {
                ChartUtils.saveChartAsPNG(imageFile, combinedChart, 1280, 720);
            } catch (IOException e) {
                e.printStackTrace();
            }
            return imageFile;
        }

        private static void addRSIThresholdLines(XYPlot rsiPlot, double lowerThreshold, double upperThreshold) {
            ValueMarker lowerMarker = new ValueMarker(lowerThreshold, Color.RED, new BasicStroke(1.5f));
            lowerMarker.setLabel("RSI " + (int) lowerThreshold);
            lowerMarker.setLabelAnchor(RectangleAnchor.TOP_LEFT);
            lowerMarker.setLabelTextAnchor(TextAnchor.BOTTOM_LEFT);
            lowerMarker.setPaint(Color.RED);
            ValueMarker upperMarker = new ValueMarker(upperThreshold, Color.RED, new BasicStroke(1.5f));
            upperMarker.setLabel("RSI " + (int) upperThreshold);
            upperMarker.setLabelAnchor(RectangleAnchor.BOTTOM_LEFT);
            upperMarker.setLabelTextAnchor(TextAnchor.TOP_LEFT);
            upperMarker.setPaint(Color.RED);
            rsiPlot.addRangeMarker(lowerMarker);
            rsiPlot.addRangeMarker(upperMarker);
        }

        private static void addMACDZeroLine(XYPlot macdPlot) {
            ValueMarker zeroMarker = new ValueMarker(0, Color.GRAY, new BasicStroke(1.5f));
            zeroMarker.setLabel("0");
            zeroMarker.setLabelAnchor(RectangleAnchor.TOP_LEFT);
            zeroMarker.setLabelTextAnchor(TextAnchor.BOTTOM_LEFT);
            macdPlot.addRangeMarker(zeroMarker);
        }

        private static void addLastPriceAnnotation(XYPlot plot, Date date, double lastPrice) {
            XYPointerAnnotation annotation = new XYPointerAnnotation(
                    String.format("%.2f", lastPrice),
                    date.getTime(),
                    lastPrice,
                    Math.PI / 2.0
            );
            annotation.setTextAnchor(TextAnchor.BASELINE_LEFT);
            annotation.setPaint(Color.RED);
            annotation.setFont(new Font("Dialog", Font.BOLD, 12));
            plot.addAnnotation(annotation);
        }

        private static double[] calculateFibonacciLevels(double high, double low) {
            double range = high - low;
            return new double[]{
                    high,
                    high - 0.236 * range,
                    high - 0.382 * range,
                    high - 0.5 * range,
                    high - 0.618 * range,
                    low + 0.236 * range,
                    low + 0.382 * range,
                    low + 0.5 * range,
                    low + 0.618 * range,
                    low
            };
        }

        private static void addFibonacciLevels(XYPlot plot, double high, double low, List<Date> dates) {
            double[] fibonacciLevels = calculateFibonacciLevels(high, low);
            Color[] colors = {Color.RED, Color.MAGENTA, Color.CYAN, Color.PINK, Color.ORANGE, Color.GREEN, Color.YELLOW, Color.BLUE, Color.DARK_GRAY, Color.BLACK};
            for (int i = 0; i < fibonacciLevels.length; i++) {
                ValueMarker marker = new ValueMarker(fibonacciLevels[i]);
                marker.setPaint(colors[i % colors.length]);
                marker.setStroke(new BasicStroke(2.0f));
                marker.setLabel(String.format("Fib %.2f", fibonacciLevels[i]));
                marker.setLabelFont(new Font("Dialog", Font.BOLD, 12));
                marker.setLabelAnchor(RectangleAnchor.TOP_LEFT);
                marker.setLabelTextAnchor(TextAnchor.BOTTOM_RIGHT);
                plot.addRangeMarker(marker);
            }
        }

        private static OHLCDataset createDataset(String symbol, List<Date> dates, double[] openPrices, double[] highPrices, double[] lowPrices, double[] closePrices, double[] volume) {
            int itemCount = dates.size();
            Date[] dateArray = dates.toArray(new Date[itemCount]);
            return new DefaultHighLowDataset(
                    symbol,
                    dateArray,
                    highPrices,
                    lowPrices,
                    openPrices,
                    closePrices,
                    volume
            );
        }

        private static void addHistoricalAnnotations(XYPlot plot, List<Date> dates, double[][] macdValues, double[] rsiValues) {
            for (int i = 0; i < dates.size(); i++) {
                if (i > 0 && i < dates.size() - 1) {
                    if (macdValues[0][i] > macdValues[1][i] && macdValues[0][i - 1] <= macdValues[1][i - 1]) {
                        ValueMarker marker = new ValueMarker(dates.get(i).getTime());
                        marker.setPaint(Color.GREEN);
                        marker.setStroke(new BasicStroke(1.0f));
                        marker.setLabel("GC");
                        marker.setLabelAnchor(RectangleAnchor.TOP_LEFT);
                        marker.setLabelTextAnchor(TextAnchor.TOP_RIGHT);
                        plot.addDomainMarker(marker);
                    } else if (macdValues[0][i] < macdValues[1][i] && macdValues[0][i - 1] >= macdValues[1][i - 1]) {
                        ValueMarker marker = new ValueMarker(dates.get(i).getTime());
                        marker.setPaint(Color.RED);
                        marker.setStroke(new BasicStroke(1.0f));
                        marker.setLabel("DC");
                        marker.setLabelAnchor(RectangleAnchor.TOP_RIGHT);
                        marker.setLabelTextAnchor(TextAnchor.TOP_LEFT);
                        plot.addDomainMarker(marker);
                    }
                }
            }
        }

        private static String generateFutureNotes(String trendDirection, double[] rsiValues, double[][] macdValues, double[] adxValues, String candlePattern) {
            double lastRSI = rsiValues[rsiValues.length - 1];
            double lastADX = adxValues[adxValues.length - 1];
            double lastMACD = macdValues[0][macdValues[0].length - 1];
            double lastSignal = macdValues[1][macdValues[1].length - 1];
            double macdDiff = lastMACD - lastSignal;

            StringBuilder notes = new StringBuilder();
            notes.append("Gelecek Notları: ");
            notes.append("Trend Yönü: ").append(trendDirection).append(". ");
            notes.append("Hesaplama Detayları: ");
            notes.append("RSI = ").append(String.format("%.2f", lastRSI)).append(" (50'den sapma: ")
                    .append(String.format("%.2f", lastRSI - 50)).append("), ");
            notes.append("ADX = ").append(String.format("%.2f", lastADX)).append(" (")
                    .append(lastADX > 25 ? "Güçlü trend" : "Zayıf trend").append("), ");
            notes.append("MACD Farkı = ").append(String.format("%.4f", macdDiff)).append(", ");
            notes.append("Mum Formasyonu = ").append(candlePattern).append(". ");
            if ("Bullish Engulfing".equals(candlePattern)) {
                notes.append("Bu formasyon, yükseliş dönüşüne işaret ediyor.");
            } else if ("Bearish Engulfing".equals(candlePattern)) {
                notes.append("Bu formasyon, düşüş dönüşüne işaret ediyor.");
            } else if ("Doji".equals(candlePattern)) {
                notes.append("Bu formasyon, piyasada kararsızlık sinyali veriyor.");
            } else if ("Hammer".equals(candlePattern)) {
                notes.append("Bu formasyon, potansiyel yükseliş dönüşüne işaret ediyor.");
            }
            return notes.toString();
        }

        private static void addFutureNotes(JFreeChart chart, String notes) {
            TextTitle futureNotesTitle = new TextTitle(notes, new Font("Dialog", Font.PLAIN, 12));
            futureNotesTitle.setPosition(RectangleEdge.BOTTOM);
            chart.addSubtitle(futureNotesTitle);
        }

        private static void addSymbolLegend(JFreeChart chart) {
            String legendText = "Legend: \u25B2 = Golden Cross, \u25BC = Death Cross";
            TextTitle legendTitle = new TextTitle(legendText, new Font("Dialog", Font.PLAIN, 12));
            legendTitle.setPosition(RectangleEdge.BOTTOM);
            chart.addSubtitle(legendTitle);
        }

        public static String generateTwitterCaption(String symbol, String trendDirection) {
            return "Closing Prices for " + symbol + " - Trend: " + trendDirection + ". Follow for more updates! #Crypto #Trading #Finance";
        }
    }

    /**
     * Telegram üzerinden görsel gönderimi yapan sınıf.
     */
    private static class TelegramNotifier {
        public static void sendTelegramImage(String botToken, long chatId, File imageFile, String caption) {
            String urlString = "https://api.telegram.org/bot" + botToken + "/sendPhoto";
            String boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW";
            HttpURLConnection con = null;
            try {
                URL url = new URL(urlString);
                con = (HttpURLConnection) url.openConnection();
                con.setRequestMethod("POST");
                con.setDoOutput(true);
                con.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                StringBuilder bodyBuilder = new StringBuilder();
                bodyBuilder.append("--").append(boundary).append("\r\n")
                        .append("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n")
                        .append(chatId).append("\r\n")
                        .append("--").append(boundary).append("\r\n")
                        .append("Content-Disposition: form-data; name=\"caption\"\r\n\r\n")
                        .append(caption).append("\r\n")
                        .append("--").append(boundary).append("\r\n")
                        .append("Content-Disposition: form-data; name=\"photo\"; filename=\"")
                        .append(imageFile.getName()).append("\"\r\n")
                        .append("Content-Type: image/png\r\n\r\n");
                byte[] bodyStart = bodyBuilder.toString().getBytes(StandardCharsets.UTF_8);
                byte[] bodyEnd = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

                try (OutputStream out = con.getOutputStream();
                     FileInputStream fileInputStream = new FileInputStream(imageFile)) {
                    out.write(bodyStart);
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                    out.write(bodyEnd);
                }
                int responseCode = con.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    System.out.println("Telegram görseli başarıyla gönderildi.");
                } else {
                    System.err.println("Telegram görsel gönderiminde hata. Response code: " + responseCode);
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(con.getErrorStream()))) {
                        String responseLine;
                        while ((responseLine = reader.readLine()) != null) {
                            System.err.println(responseLine);
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (con != null) {
                    con.disconnect();
                }
            }
        }
    }
}
