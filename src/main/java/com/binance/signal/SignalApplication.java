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
import org.jfree.data.xy.OHLCDataset;
import org.jfree.data.xy.DefaultHighLowDataset;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.jfree.chart.annotations.XYPointerAnnotation;
import org.jfree.chart.ui.RectangleEdge;
import org.json.JSONArray;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.awt.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.stream.Stream;

@SpringBootApplication
public class SignalApplication {

    private static final Map<String, String> SYMBOLS_AND_API_URLS = new HashMap<>();
    private static final String SYMBOLS_AND_API_URLS_FILE = "SymbolsAndApiUrl";

    static {
        loadSymbolsAndApiUrlsFromFile();
    }

    private static void loadSymbolsAndApiUrlsFromFile() {
        try {
            Path path = Paths.get("src/main/resources/" + SYMBOLS_AND_API_URLS_FILE);
            try (Stream<String> lines = Files.lines(path)) {
                lines.forEach(line -> {
                    String[] parts = line.split(",");
                    if (parts.length == 2) {
                        SYMBOLS_AND_API_URLS.put(parts[0].trim(), parts[1].trim());
                    }
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("SymbolsAndApiUrl dosyası okunamadı!");
        }
    }

    public static void main(String[] args) {
        String botToken = "6482508265:AAEDUmyCM-ygU7BVO-txyykS7cKn5URspmY";  // Bot tokenınızı buraya yazın
        long chatId = 1692398446;           // Chat ID'nizi buraya yazın

        while (true) {
            try {
                for (Map.Entry<String, String> entry : SYMBOLS_AND_API_URLS.entrySet()) {
                    String symbol = entry.getKey();
                    String apiUrl = entry.getValue();

                    // Her bir sembol için API verilerini al
                    URL url = new URL(apiUrl);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");

                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    // JSON verisini işle
                    JSONArray klines = new JSONArray(response.toString());
                    int dataCount = klines.length();
                    double[] openPrices = new double[dataCount];
                    double[] highPrices = new double[dataCount];
                    double[] lowPrices = new double[dataCount];
                    double[] closePrices = new double[dataCount];
                    double[] volume = new double[dataCount];
                    List<Date> dates = new ArrayList<>();

                    for (int i = 0; i < dataCount; i++) {
                        JSONArray kline = klines.getJSONArray(i);
                        openPrices[i] = kline.getDouble(1);
                        highPrices[i] = kline.getDouble(2);
                        lowPrices[i] = kline.getDouble(3);
                        closePrices[i] = kline.getDouble(4);
                        volume[i] = kline.getDouble(5);
                        long timestamp = kline.getLong(0);
                        dates.add(new Date(timestamp));
                    }

                    // Temel göstergeleri hesapla
                    double[] movingAverages = calculateMovingAverage(closePrices, 20); // 20 günlük MA
                    double[] rsiValues = calculateRSI(closePrices, 14); // 14 günlük RSI
                    double[][] macdValues = calculateMACD(closePrices); // MACD ve sinyal hattı
                    double[][] bollingerBands = calculateBollingerBands(closePrices);

                    // Ek göstergeler
                    double[] adxValues = calculateADX(highPrices, lowPrices, closePrices, 14);
                    double[] obvValues = calculateOBV(closePrices, volume);
                    String candlePattern = analyzeCandlestickPatterns(openPrices, closePrices, highPrices, lowPrices);

                    // Ağırlıklı oylama yöntemi ile trend tahmini (tek zaman dilimi)
                    String weightedTrend = determineTrendDirection(closePrices, movingAverages, rsiValues, macdValues);

                    // Çok zamanlı analiz (son 20 veri ile kısa vadeli trend ve tüm verinin trendinin karşılaştırılması)
                    String multiTrend = multiTimeframeAnalysis(closePrices, movingAverages, rsiValues, macdValues, adxValues, obvValues);

                    // Final trend; tüm göstergeler göz önüne alınarak karar veriliyor
                    String finalTrend = determineFinalTrend(weightedTrend, multiTrend, adxValues, candlePattern);

                    // Grafik oluştur ve Telegram'a gönder
                    File chartFile = generateTradingViewStyleChart(symbol, dates, openPrices, highPrices, lowPrices, closePrices, volume, movingAverages, rsiValues, macdValues, bollingerBands, finalTrend);
                    String caption = generateTwitterCaption(symbol, finalTrend);
                    sendTelegramImage(botToken, chatId, chartFile, caption);

                    // Bağlantıyı kapat
                    connection.disconnect();
                }

                Thread.sleep(3600000); // 2 saat bekle

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // --- Temel Göstergeler ---
    private static double[] calculateMovingAverage(double[] prices, int period) {
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

    private static double[] calculateRSI(double[] prices, int period) {
        double[] rsi = new double[prices.length];
        double gain = 0;
        double loss = 0;
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

    private static double[][] calculateMACD(double[] prices) {
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
        return new double[][] { macd, signal, histogram };
    }

    private static double[] calculateEMA(double[] prices, int period) {
        double[] ema = new double[prices.length];
        double multiplier = 2.0 / (period + 1);
        ema[0] = prices[0];
        for (int i = 1; i < prices.length; i++) {
            ema[i] = ((prices[i] - ema[i - 1]) * multiplier) + ema[i - 1];
        }
        return ema;
    }

    private static double[][] calculateBollingerBands(double[] prices) {
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
        return new double[][] { upperBand, middleBand, lowerBand };
    }

    private static double[] calculateSMA(double[] prices, int period) {
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

    // --- Ek Göstergeler ---

    // ADX hesaplaması
    private static double[] calculateADX(double[] high, double[] low, double[] close, int period) {
        int n = high.length;
        double[] tr = new double[n];
        double[] plusDM = new double[n];
        double[] minusDM = new double[n];
        tr[0] = 0;
        plusDM[0] = 0;
        minusDM[0] = 0;
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
        double atrSum = 0;
        double plusDMSum = 0;
        double minusDMSum = 0;
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

    // OBV hesaplaması
    private static double[] calculateOBV(double[] closePrices, double[] volume) {
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

    // Candlestick pattern analizi (son 2 mum üzerinden)
    private static String analyzeCandlestickPatterns(double[] openPrices, double[] closePrices, double[] highPrices, double[] lowPrices) {
        int n = openPrices.length;
        if (n < 2) return "None";

        // Son iki mum üzerinden analiz yapıyoruz
        double openPrev = openPrices[n - 2], closePrev = closePrices[n - 2];
        double openLast = openPrices[n - 1], closeLast = closePrices[n - 1];

        // Bullish Engulfing: Önceki mum bearish, son mum bullish; tolerans %1
        if (closePrev < openPrev && closeLast > openLast
                && openLast < closePrev * 1.01 && closeLast > openPrev * 0.99) {
            return "Bullish Engulfing";
        }

        // Bearish Engulfing: Önceki mum bullish, son mum bearish; tolerans %1
        if (closePrev > openPrev && closeLast < openLast
                && openLast > closePrev * 0.99 && closeLast < openPrev * 1.01) {
            return "Bearish Engulfing";
        }

        // Doji: Gövdenin, mumun toplam aralığına oranı %15'ten küçükse
        double body = Math.abs(closeLast - openLast);
        double range = highPrices[n - 1] - lowPrices[n - 1];
        if (range > 0 && (body / range) < 0.15) {
            return "Doji";
        }

        // Hammer: Alt gölge gövdenin en az iki katı, üst gölge gövdenin altında ise
        double lowerShadow = Math.min(openLast, closeLast) - lowPrices[n - 1];
        double upperShadow = highPrices[n - 1] - Math.max(openLast, closeLast);
        if (body > 0 && lowerShadow >= 2 * body && upperShadow < body) {
            return "Hammer";
        }

        return "None";
    }


    // Çok zamanlı analiz: son 20 veri (kısa vadeli) ile tüm veri (uzun vadeli) trendlerini karşılaştırır
    private static String multiTimeframeAnalysis(double[] closePrices, double[] movingAverages, double[] rsiValues, double[][] macdValues, double[] adxValues, double[] obvValues) {
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
        if (shortTrend.equals(longTrend)) {
            return shortTrend;
        } else {
            return "Mixed";
        }
    }

    // --- Yön Tahmini ---
    /**
     * Ağırlıklı oylama yöntemi:
     * - Fiyatın MA'ya göre oransal farkı
     * - RSI’nın 50’den sapması (normalize edilmiş)
     * - MACD ile sinyal hattı farkının tanh dönüşümü
     */
    private static String determineTrendDirection(double[] closingPrices, double[] movingAverages, double[] rsiValues, double[][] macdValues) {
        double totalBullishScore = 0.0;
        double totalBearishScore = 0.0;
        // MA kontrolü
        double lastPrice = closingPrices[closingPrices.length - 1];
        double lastMA = movingAverages[movingAverages.length - 1];
        if (!Double.isNaN(lastMA) && lastMA != 0) {
            double maDiff = (lastPrice - lastMA) / lastMA;
            if (maDiff > 0) {
                totalBullishScore += maDiff;
            } else if (maDiff < 0) {
                totalBearishScore += -maDiff;
            }
        }
        // RSI kontrolü
        double lastRSI = rsiValues[rsiValues.length - 1];
        if (!Double.isNaN(lastRSI)) {
            double rsiDiff = lastRSI - 50;
            if (rsiDiff > 0) {
                totalBullishScore += rsiDiff / 50;
            } else if (rsiDiff < 0) {
                totalBearishScore += -rsiDiff / 50;
            }
        }
        // MACD kontrolü
        double lastMACD = macdValues[0][macdValues[0].length - 1];
        double lastSignal = macdValues[1][macdValues[1].length - 1];
        double macdDiff = lastMACD - lastSignal;
        double macdWeight = Math.tanh(macdDiff);
        if (macdWeight > 0) {
            totalBullishScore += macdWeight;
        } else if (macdWeight < 0) {
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

    // Final trend belirlemesi: Ağırlıklı ve çok zamanlı analiz, ADX ve candlestick pattern göz önüne alınır.
    private static String determineFinalTrend(String weightedTrend, String multiTrend, double[] adxValues, String candlePattern) {
        double lastADX = adxValues[adxValues.length - 1];
        boolean strongTrend = lastADX > 25;
        if (candlePattern.equals("Bullish Engulfing") && (weightedTrend.equals("Bullish") || multiTrend.equals("Bullish"))) {
            return "Bullish";
        } else if (candlePattern.equals("Bearish Engulfing") && (weightedTrend.equals("Bearish") || multiTrend.equals("Bearish"))) {
            return "Bearish";
        }
        if (weightedTrend.equals(multiTrend)) {
            return weightedTrend;
        } else {
            return strongTrend ? weightedTrend : "Mixed";
        }
    }

    // --- Telegram Gönderim Fonksiyonu ---
    private static void sendTelegramImage(String botToken, long chatId, File imageFile, String caption) {
        String urlString = "https://api.telegram.org/bot" + botToken + "/sendPhoto";
        String boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW";
        try {
            URL url = new URL(urlString);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
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
            byte[] bodyStart = bodyBuilder.toString().getBytes("UTF-8");
            byte[] bodyEnd = ("\r\n--" + boundary + "--\r\n").getBytes("UTF-8");
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
                System.out.println("Telegram image sent successfully.");
            } else {
                System.err.println("Error sending image to Telegram. Response code: " + responseCode);
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(con.getErrorStream()))) {
                    String responseLine;
                    while ((responseLine = reader.readLine()) != null) {
                        System.err.println(responseLine);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // --- Grafik Oluşturma ---
    private static File generateTradingViewStyleChart(String symbol, List<Date> dates,
                                                      double[] openPrices, double[] highPrices,
                                                      double[] lowPrices, double[] closePrices,
                                                      double[] volume, double[] movingAverages,
                                                      double[] rsiValues, double[][] macdValues,
                                                      double[][] bollingerBands, String trendDirection) {
        // OHLC dataset
        OHLCDataset dataset = createDataset(symbol, dates, openPrices, highPrices, lowPrices, closePrices, volume);

        // 1) Candlestick Chart oluştur
        JFreeChart candlestickChart = ChartFactory.createCandlestickChart(
                symbol + " Closing Prices",
                "Time",
                "Price",
                dataset,
                false
        );

        // 2) Anti-Aliasing ile daha net görüntü
        candlestickChart.setAntiAlias(true);

        // 3) Candlestick Plot
        XYPlot candlestickPlot = (XYPlot) candlestickChart.getPlot();
        candlestickPlot.setBackgroundPaint(Color.WHITE); // Arka plan beyaz
        candlestickPlot.setDomainGridlinePaint(Color.LIGHT_GRAY); // Dikey grid çizgileri
        candlestickPlot.setRangeGridlinePaint(Color.LIGHT_GRAY);  // Yatay grid çizgileri
        candlestickPlot.setOutlineVisible(false); // Dış kenarlığı kapat

        // Candlestick Renderer ayarları (isteğe göre renk değişikliği)
        CandlestickRenderer candlestickRenderer = new CandlestickRenderer();
        // Örnek: yeşil-kırmızı yerine farklı renkler kullanmak isterseniz
        candlestickRenderer.setUpPaint(new Color(34, 177, 76));   // yükseliş mum rengi
        candlestickRenderer.setDownPaint(new Color(237, 28, 36)); // düşüş mum rengi
        candlestickPlot.setRenderer(candlestickRenderer);

        // 4) Price Axis ayarları
        NumberAxis priceAxis = (NumberAxis) candlestickPlot.getRangeAxis();
        priceAxis.setAutoRangeIncludesZero(false);
        priceAxis.setLabelFont(new Font("Dialog", Font.BOLD, 14));
        priceAxis.setTickLabelFont(new Font("Dialog", Font.PLAIN, 12));
        priceAxis.setTickLabelPaint(Color.BLACK);

        // 5) Hareketli Ortalama serisi
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
        maRenderer.setSeriesStroke(0, new BasicStroke(2.0f)); // Çizgi kalınlığı
        candlestickPlot.setRenderer(1, maRenderer);

        // 6) Bollinger Bantları
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

        // 7) Tarih ekseni
        DateAxis dateAxis = new DateAxis("Time");
        dateAxis.setDateFormatOverride(new SimpleDateFormat("MMM-yyyy"));
        dateAxis.setTickUnit(new DateTickUnit(DateTickUnitType.MONTH, 1));
        // Tarih etiketlerini yatay yapmak için:
        dateAxis.setVerticalTickLabels(false);
        dateAxis.setTickLabelFont(new Font("Dialog", Font.PLAIN, 10));
        candlestickPlot.setDomainAxis(dateAxis);

        // 8) Geçmiş önemli olaylar, Fibonacci seviyeleri, son fiyat anotasyonu
        addHistoricalAnnotations(candlestickPlot, dates, macdValues, rsiValues);
        double high = Arrays.stream(highPrices).max().orElse(Double.NaN);
        double low = Arrays.stream(lowPrices).min().orElse(Double.NaN);
        addFibonacciLevels(candlestickPlot, high, low, dates);
        addLastPriceAnnotation(candlestickPlot, dates.get(dates.size() - 1), closePrices[closePrices.length - 1]);

        // 9) RSI Plot
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

        // --> RSI'ya 30 ve 70 seviyeleri için yatay çizgi ekleyelim
        addRSIThresholdLines(rsiPlot, 30, 70);

        // 10) MACD Plot
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

        // --> MACD grafiğinde 0 seviyesini göstermek için yatay çizgi ekleyelim
        addMACDZeroLine(macdPlot);

        // 11) Combined plot
        CombinedDomainXYPlot combinedPlot = new CombinedDomainXYPlot(dateAxis);
        combinedPlot.setGap(10.0);
        combinedPlot.add(candlestickPlot, 3); // candlestickPlot %60
        combinedPlot.add(rsiPlot, 1);        // rsiPlot %20
        combinedPlot.add(macdPlot, 1);       // macdPlot %20

        // 12) Chart oluşturma
        JFreeChart combinedChart = new JFreeChart(
                symbol + " Closing Prices",
                JFreeChart.DEFAULT_TITLE_FONT,
                combinedPlot,
                true
        );

        // Arka plana gradient eklemek isterseniz (opsiyonel)
        // combinedChart.setBackgroundPaint(new GradientPaint(0, 0, Color.WHITE, 0, 600, Color.LIGHT_GRAY));

        Font font = new Font("Dialog", Font.PLAIN, 14);
        combinedPlot.getDomainAxis().setTickLabelFont(font);
        candlestickPlot.getRangeAxis().setTickLabelFont(font);
        rsiPlot.getRangeAxis().setTickLabelFont(font);
        macdPlot.getRangeAxis().setTickLabelFont(font);

        // 13) Gelecek notları (future notes) ve diğer alt başlıklar
        String futureNotes = generateFutureNotes(
                trendDirection, rsiValues, macdValues,
                calculateADX(highPrices, lowPrices, closePrices, 14),
                analyzeCandlestickPatterns(openPrices, closePrices, highPrices, lowPrices)
        );
        addFutureNotes(combinedChart, futureNotes);
        addSymbolLegend(combinedChart);

        TextTitle trendTitle = new TextTitle("Trend Direction: " + trendDirection, new Font("Dialog", Font.BOLD, 14));
        trendTitle.setPosition(RectangleEdge.TOP);
        combinedChart.addSubtitle(trendTitle);

        // 14) Chart'ı kaydet
        File imageFile = new File("trading_view_style_chart_" + symbol + ".png");
        try {
            // Daha yüksek çözünürlük isterseniz boyutları artırabilirsiniz (örn. 1280, 720)
            ChartUtils.saveChartAsPNG(imageFile, combinedChart, 1280, 720);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return imageFile;
    }

    /**
     * RSI için eşik çizgileri ekleme (örn. 30 ve 70 seviyeleri).
     */
    private static void addRSIThresholdLines(XYPlot rsiPlot, double lowerThreshold, double upperThreshold) {
        ValueMarker lowerMarker = new ValueMarker(lowerThreshold, Color.RED, new BasicStroke(1.5f));
        lowerMarker.setLabel("RSI " + (int)lowerThreshold);
        lowerMarker.setLabelAnchor(RectangleAnchor.TOP_LEFT);
        lowerMarker.setLabelTextAnchor(TextAnchor.BOTTOM_LEFT);
        lowerMarker.setPaint(Color.RED);

        ValueMarker upperMarker = new ValueMarker(upperThreshold, Color.RED, new BasicStroke(1.5f));
        upperMarker.setLabel("RSI " + (int)upperThreshold);
        upperMarker.setLabelAnchor(RectangleAnchor.BOTTOM_LEFT);
        upperMarker.setLabelTextAnchor(TextAnchor.TOP_LEFT);
        upperMarker.setPaint(Color.RED);

        rsiPlot.addRangeMarker(lowerMarker);
        rsiPlot.addRangeMarker(upperMarker);
    }

    /**
     * MACD grafiğinde 0 seviyesini yatay çizgi ile göstermek.
     */
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
        StringBuilder notes = new StringBuilder();
        notes.append("Gelecek Notları: ");

        // Trend Yönü Açıklaması
        switch (trendDirection) {
            case "Bullish":
                notes.append("Yükseliş trendi tespit edildi: Fiyat ve göstergeler yukarı yönlü hareketi destekliyor. ");
                break;
            case "Bearish":
                notes.append("Düşüş trendi tespit edildi: Fiyat ve göstergeler aşağı yönlü hareketi işaret ediyor. ");
                break;
            case "Mixed":
                notes.append("Karışık sinyaller: Kısa vadeli ve uzun vadeli analizler farklı sonuçlar veriyor, belirsizlik mevcut. ");
                break;
            default:
                notes.append("Nötr trend: Belirgin bir yön sinyali gözlemlenmiyor. ");
                break;
        }

        // RSI Açıklaması
        double lastRSI = rsiValues[rsiValues.length - 1];
        notes.append("RSI değeri ").append(String.format("%.2f", lastRSI)).append(". ");
        if (lastRSI > 70) {
            notes.append("Bu, aşırı alım durumunu gösteriyor ve potansiyel olarak bir dönüş yaşanabileceğini işaret ediyor. ");
        } else if (lastRSI < 30) {
            notes.append("Bu, aşırı satım durumunu gösteriyor ve potansiyel olarak bir dönüş yaşanabileceğini işaret ediyor. ");
        } else {
            notes.append("RSI nötr bölgede, belirgin bir sinyal vermiyor. ");
        }

        // ADX Açıklaması
        double lastADX = adxValues[adxValues.length - 1];
        notes.append("ADX değeri ").append(String.format("%.2f", lastADX)).append(", ");
        if (lastADX > 25) {
            notes.append("bu, güçlü bir trendin varlığını gösteriyor. ");
        } else {
            notes.append("bu, zayıf bir trendin varlığını gösteriyor. ");
        }

        // Candlestick Pattern Açıklaması
        notes.append("Tespit edilen mum formasyonu: ").append(candlePattern).append(". ");
        if ("Bullish Engulfing".equals(candlePattern)) {
            notes.append("Bu formasyon, düşüş trendinin tersine dönerek yükselişin başlayabileceğini gösterir. ");
        } else if ("Bearish Engulfing".equals(candlePattern)) {
            notes.append("Bu formasyon, yükseliş trendinin tersine dönerek düşüşün başlayabileceğini gösterir. ");
        } else if ("Doji".equals(candlePattern)) {
            notes.append("Bu formasyon, piyasada kararsızlık olduğunu ve potansiyel bir dönüş yaşanabileceğini gösterir. ");
        } else if ("Hammer".equals(candlePattern)) {
            notes.append("Bu formasyon, özellikle düşüş trendinin sonunda görüldüğünde, potansiyel bir yükseliş dönüşüne işaret eder. ");
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

    private static String generateTwitterCaption(String symbol, String trendDirection) {
        return "Closing Prices for " + symbol + " - Trend: " + trendDirection + ". Follow for more updates! #Crypto #Trading #Finance";
    }
}
