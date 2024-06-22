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
        String botToken = "6482508265:AAEDUmyCM-ygU7BVO-txyykS7cKn5URspmY";  // Replace with your actual bot token
        long chatId = 1692398446;           // Replace with your actual chat ID

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
                    double[] openPrices = new double[klines.length()];
                    double[] highPrices = new double[klines.length()];
                    double[] lowPrices = new double[klines.length()];
                    double[] closePrices = new double[klines.length()];
                    double[] volume = new double[klines.length()];
                    List<Date> dates = new ArrayList<>();

                    for (int i = 0; i < klines.length(); i++) {
                        JSONArray kline = klines.getJSONArray(i);
                        openPrices[i] = kline.getDouble(1);
                        highPrices[i] = kline.getDouble(2);
                        lowPrices[i] = kline.getDouble(3);
                        closePrices[i] = kline.getDouble(4);
                        volume[i] = kline.getDouble(5);
                        long timestamp = kline.getLong(0);
                        dates.add(new Date(timestamp));
                    }

                    // Hareketli ortalama hesapla
                    double[] movingAverages = calculateMovingAverage(closePrices, 20); // 20 günlük hareketli ortalama

                    // RSI hesapla
                    double[] rsiValues = calculateRSI(closePrices, 14); // 14 günlük RSI

                    // MACD hesapla
                    double[][] macdValues = calculateMACD(closePrices); // MACD ve sinyal hattı

                    // Bollinger Bantları hesapla
                    double[][] bollingerBands = calculateBollingerBands(closePrices);

                    // Trend yönünü belirle
                    String trendDirection = determineTrendDirection(closePrices, movingAverages, rsiValues);

                    // Grafik oluştur ve Telegram'a gönder
                    File chartFile = generateTradingViewStyleChart(symbol, dates, openPrices, highPrices, lowPrices, closePrices, volume, movingAverages, rsiValues, macdValues, bollingerBands, trendDirection);
                    String caption = "Closing Prices for " + symbol + " - Trend: " + trendDirection;
                    sendTelegramImage(botToken, chatId, chartFile, caption);

                    // Bağlantıyı kapat
                    connection.disconnect();
                }

                Thread.sleep(86400000); // 24 saat bekle

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static double[] calculateMovingAverage(double[] prices, int period) {
        double[] movingAverages = new double[prices.length];
        for (int i = 0; i < prices.length; i++) {
            if (i < period - 1) {
                movingAverages[i] = Double.NaN; // Yeterli veri yoksa NaN (Not a Number) olarak ayarla
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

        // İlk 'period' kadar değeri NaN olarak ayarlayalım
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

        return new double[][]{macd, signal, histogram};
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

        return new double[][]{upperBand, middleBand, lowerBand};
    }

    private static double[] calculateSMA(double[] prices, int period) {
        double[] sma = new double[prices.length];
        for (int i = 0; i < prices.length; i++) {
            if (i < period - 1) {
                sma[i] = Double.NaN; // Yeterli veri yoksa NaN (Not a Number) olarak ayarla
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

    private static String determineTrendDirection(double[] closingPrices, double[] movingAverages, double[] rsiValues) {
        double lastPrice = closingPrices[closingPrices.length - 1];
        double lastMA = movingAverages[movingAverages.length - 1];
        double secondLastMA = movingAverages[movingAverages.length - 2];
        double lastRSI = rsiValues[rsiValues.length - 1];

        if (lastPrice > lastMA && lastMA > secondLastMA && lastRSI > 50) {
            return "Bullish";
        } else if (lastPrice < lastMA && lastMA < secondLastMA && lastRSI < 50) {
            return "Bearish";
        } else {
            return "Neutral";
        }
    }

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

    private static File generateTradingViewStyleChart(String symbol, List<Date> dates, double[] openPrices, double[] highPrices, double[] lowPrices, double[] closePrices, double[] volume, double[] movingAverages, double[] rsiValues, double[][] macdValues, double[][] bollingerBands, String trendDirection) {
        // OHLC verisetini oluştur
        OHLCDataset dataset = createDataset(symbol, dates, openPrices, highPrices, lowPrices, closePrices, volume);

        // Candlestick plot
        JFreeChart candlestickChart = ChartFactory.createCandlestickChart(
                symbol + " Closing Prices",
                "Time",
                "Price",
                dataset,
                false
        );
        XYPlot candlestickPlot = (XYPlot) candlestickChart.getPlot();
        CandlestickRenderer candlestickRenderer = new CandlestickRenderer();
        candlestickPlot.setRenderer(candlestickRenderer);

        // Hareketli ortalama serisini ekle
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
        candlestickPlot.setRenderer(1, maRenderer);

        // Bollinger Bantları serisini ekle
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
        bollingerRenderer.setSeriesPaint(1, Color.GRAY);
        bollingerRenderer.setSeriesPaint(2, Color.DARK_GRAY);
        candlestickPlot.setRenderer(2, bollingerRenderer);

        // Geçmiş önemli olayları ekle
        addHistoricalAnnotations(candlestickPlot, dates, macdValues, rsiValues);

        // RSI plot
        XYPlot rsiPlot = new XYPlot();
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
        rsiPlot.setRenderer(rsiRenderer);

        // MACD plot
        XYPlot macdPlot = new XYPlot();
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
        macdRenderer.setSeriesPaint(1, Color.YELLOW);
        macdRenderer.setSeriesPaint(2, Color.CYAN);
        macdPlot.setRenderer(macdRenderer);

        // Combined plot
        CombinedDomainXYPlot combinedPlot = new CombinedDomainXYPlot(new DateAxis("Time"));
        combinedPlot.setGap(10.0);
        combinedPlot.add(candlestickPlot, 3); // Candlestick plot will take 60% of the space
        combinedPlot.add(rsiPlot, 1); // RSI plot will take 20% of the space
        combinedPlot.add(macdPlot, 1); // MACD plot will take 20% of the space

        JFreeChart combinedChart = new JFreeChart(symbol + " Closing Prices", JFreeChart.DEFAULT_TITLE_FONT, combinedPlot, true);

        // Yazı tipini büyüt
        Font font = new Font("Dialog", Font.PLAIN, 14);
        combinedPlot.getDomainAxis().setTickLabelFont(font);
        candlestickPlot.getRangeAxis().setTickLabelFont(font);
        rsiPlot.getRangeAxis().setTickLabelFont(font);
        macdPlot.getRangeAxis().setTickLabelFont(font);

        // Gelecek tahminlerini ve notları ekle
        String futureNotes = generateFutureNotes(trendDirection, rsiValues, macdValues);
        addFutureNotes(combinedChart, futureNotes);

        // Sembol tablosunu ekle
        addSymbolLegend(combinedChart);

        // Trend yönünü ekle
        TextTitle trendTitle = new TextTitle("Trend Direction: " + trendDirection, new Font("Dialog", Font.BOLD, 14));
        trendTitle.setPosition(RectangleEdge.TOP);
        combinedChart.addSubtitle(trendTitle);

        File imageFile = new File("trading_view_style_chart_" + symbol + ".png");
        try {
            ChartUtils.saveChartAsPNG(imageFile, combinedChart, 1024, 768);
        } catch (IOException e) {
            e.printStackTrace();
        }

        return imageFile;
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
                    // Golden Cross (Bullish Signal)
                    ValueMarker marker = new ValueMarker(dates.get(i).getTime());
                    marker.setPaint(Color.GREEN);
                    marker.setStroke(new BasicStroke(1.0f));
                    marker.setLabel("GC");
                    marker.setLabelAnchor(RectangleAnchor.TOP_LEFT);
                    marker.setLabelTextAnchor(TextAnchor.TOP_RIGHT);
                    plot.addDomainMarker(marker);
                } else if (macdValues[0][i] < macdValues[1][i] && macdValues[0][i - 1] >= macdValues[1][i - 1]) {
                    // Death Cross (Bearish Signal)
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

    private static String generateFutureNotes(String trendDirection, double[] rsiValues, double[][] macdValues) {
        StringBuilder notes = new StringBuilder();
        notes.append("Future Notes: ");
        if ("Bullish".equals(trendDirection)) {
            notes.append("Expect potential bullish trend continuation.");
        } else if ("Bearish".equals(trendDirection)) {
            notes.append("Expect potential bearish trend continuation.");
        } else {
            notes.append("Trend is neutral, watch for further signals.");
        }

        double lastRSI = rsiValues[rsiValues.length - 1];
        if (lastRSI > 70) {
            notes.append(" RSI is overbought, consider caution.");
        } else if (lastRSI < 30) {
            notes.append(" RSI is oversold, potential for reversal.");
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


}