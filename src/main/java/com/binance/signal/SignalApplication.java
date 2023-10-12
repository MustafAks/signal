package com.binance.signal;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

@SpringBootApplication
public class SignalApplication {

    private static final Map<String, String> SYMBOLS_AND_API_URLS = new HashMap<>();

    private static final int PERIOD = 20; // RSI ve ADX hesaplama periyodu
    private static final int BOLLINGER_PERIOD = 21; // Bollinger Bantları periyodu
    private static final int FIBONACCI_PERIOD = 50; // Fibonacci geri çekilme seviyeleri için periyodu

    private static String previousTrendDirection = "";

    private static final String SYMBOLS_AND_API_URLS_FILE = "SymbolsAndApiUrl";

    static {
        loadSymbolsAndApiUrlsFromFile();
    }

    private static void loadSymbolsAndApiUrlsFromFile() {
        try {
            Path path = Paths.get("src/main/resources/" + SignalApplication.SYMBOLS_AND_API_URLS_FILE);

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
                    double[] closingPrices = new double[klines.length()];
                    double[] highPrices = new double[klines.length()];
                    double[] lowPrices = new double[klines.length()];

                    for (int i = 0; i < klines.length(); i++) {
                        JSONArray kline = klines.getJSONArray(i);
                        double closingPrice = Double.parseDouble(kline.getString(4));
                        double highPrice = Double.parseDouble(kline.getString(2));
                        double lowPrice = Double.parseDouble(kline.getString(3));

                        closingPrices[i] = closingPrice;
                        highPrices[i] = highPrice;
                        lowPrices[i] = lowPrice;
                    }

                    // Tarih bilgisini al
                    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    String dateStr = dateFormat.format(new Date());

                    // Trend analizi yap ve sonucu yazdır
                    analyzeTrend(symbol, dateStr, closingPrices, highPrices, lowPrices);

                    // Bağlantıyı kapat
                    connection.disconnect();
                }

                Thread.sleep(300000); // 5 dakika bekle


            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static double calculateRSI_EMA(double[] closingPrices) {
        double alpha = 1.0 / PERIOD;
        double gain = 0;
        double loss = 0;

        for (int i = 1; i <= PERIOD; i++) {
            double change = closingPrices[i] - closingPrices[i - 1];
            if (change >= 0) {
                gain += change;
            } else {
                loss -= change;
            }
        }
        double avgGain = gain / PERIOD;
        double avgLoss = loss / PERIOD;

        for (int i = PERIOD + 1; i < closingPrices.length; i++) {
            double change = closingPrices[i] - closingPrices[i - 1];
            if (change >= 0) {
                avgGain = (avgGain * (PERIOD - 1) + change) * alpha;
                avgLoss = avgLoss * (1 - alpha);
            } else {
                avgLoss = (avgLoss * (PERIOD - 1) - change) * alpha;
                avgGain = avgGain * (1 - alpha);
            }
        }

        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }


    private static double calculateSMA(double[] closingPrices) {
        double sum = 0;

        for (int i = 0; i < PERIOD; i++) {
            sum += closingPrices[i];
        }

        double sma = sum / PERIOD;
        return sma;
    }

    private static double calculateEMA(double[] closingPrices) {
        double ema = 0;
        double multiplier = 2.0 / (PERIOD + 1);

        for (int i = 0; i < closingPrices.length; i++) {
            if (i == 0) {
                ema = closingPrices[i];
            } else {
                ema = (closingPrices[i] - ema) * multiplier + ema;
            }
        }

        return ema;
    }

    private static double[][] calculateMACD(double[] closingPrices) {
        int shortTermPeriod = 9; // Kısa süreli EMA periyodu
        int longTermPeriod = 21; // Uzun süreli EMA periyodu
        int signalPeriod = 9;

        if (closingPrices.length < longTermPeriod + signalPeriod) {
            // Yeterli veri yoksa dizi döndürülmez.
            return new double[][]{{0, 0}, {0, 0}};
        }

        double[] macdValues = new double[closingPrices.length];
        double[] signalValues = new double[closingPrices.length];

        // MACD değerlerini hesaplama
        for (int i = longTermPeriod; i < closingPrices.length; i++) {
            double shortTermEma = calculateEMA(Arrays.copyOfRange(closingPrices, i - shortTermPeriod + 1, i + 1));
            double longTermEma = calculateEMA(Arrays.copyOfRange(closingPrices, i - longTermPeriod + 1, i + 1));
            macdValues[i] = shortTermEma - longTermEma;
        }

        // MACD'nin sinyal hattı değerlerini hesaplama
        for (int i = longTermPeriod + signalPeriod - 1; i < macdValues.length; i++) {
            signalValues[i] = calculateEMA(Arrays.copyOfRange(macdValues, i - signalPeriod + 1, i + 1));
        }

        double[][] results = new double[2][2];
        results[0][0] = macdValues[closingPrices.length - 1];  // En son MACD değeri
        results[0][1] = signalValues[closingPrices.length - 1];  // En son MACD sinyal değeri

        results[1][0] = macdValues[closingPrices.length - 2];  // Bir önceki MACD değeri
        results[1][1] = signalValues[closingPrices.length - 2];  // Bir önceki MACD sinyal değeri

        return results;
    }


    private static double[][] calculateStochasticOscillator(double[] highPrices, double[] lowPrices, double[] closingPrices) {
        int period = 14; // Stokastik Osilatör hesaplama periyodu
        int slowPeriod = 3; // Yavaş stokastik osilatör hesaplama periyodu

        if (highPrices.length < period) {
            return new double[][]{}; // Yeterli veri yoksa, Stokastik Osilatör hesaplamasını yapmayı beklemeyi tercih edebilirsiniz.
        }

        double[] stochasticOscillator = new double[closingPrices.length - period + 1];
        double[] slowStochasticOscillator = new double[closingPrices.length - period - slowPeriod + 2];

        for (int i = period - 1; i < closingPrices.length; i++) {
            double[] highPricesSubset = Arrays.copyOfRange(highPrices, i - period + 1, i + 1);
            double[] lowPricesSubset = Arrays.copyOfRange(lowPrices, i - period + 1, i + 1);

            double highestHigh = Arrays.stream(highPricesSubset).max().orElse(0);
            double lowestLow = Arrays.stream(lowPricesSubset).min().orElse(0);

            double currentClose = closingPrices[i];

            double stochasticOscillatorValue = ((currentClose - lowestLow) / (highestHigh - lowestLow)) * 100;
            stochasticOscillator[i - (period - 1)] = stochasticOscillatorValue;
        }

        for (int i = 0; i < stochasticOscillator.length - slowPeriod + 1; i++) {
            double sum = 0;
            for (int j = i; j < i + slowPeriod; j++) {
                sum += stochasticOscillator[j];
            }
            slowStochasticOscillator[i] = sum / slowPeriod;
        }

        return new double[][]{stochasticOscillator, slowStochasticOscillator};
    }


    private static double[] calculateBollingerBands(double[] closingPrices) {
        int bollingerPeriod = 20; // Bollinger Bantları periyodu
        double bollingerMultiplier = 2.0; // Bollinger Bantları çarpanı

        if (closingPrices.length < bollingerPeriod) {
            // Yeterli veri yoksa, Bollinger Bantları hesaplamasını yapmayı beklemeyi tercih edebilirsiniz.
            return new double[]{0, 0, 0}; // Orta Bollinger Bandı, Üst Bollinger Bandı, Alt Bollinger Bandı
        }

        double[] middleBand = new double[closingPrices.length - bollingerPeriod + 1];
        double[] upperBand = new double[closingPrices.length - bollingerPeriod + 1];
        double[] lowerBand = new double[closingPrices.length - bollingerPeriod + 1];

        for (int i = bollingerPeriod - 1, j = 0; i < closingPrices.length; i++, j++) {
            double[] subset = Arrays.copyOfRange(closingPrices, i - bollingerPeriod + 1, i + 1);
            double sma = calculateSMA(subset);
            double stdDev = calculateStandardDeviation(subset);

            middleBand[j] = sma;
            upperBand[j] = sma + (bollingerMultiplier * stdDev);
            lowerBand[j] = sma - (bollingerMultiplier * stdDev);
        }

        return new double[]{middleBand[middleBand.length - 1], upperBand[upperBand.length - 1], lowerBand[lowerBand.length - 1]};
    }

    private static double calculateStandardDeviation(double[] values) {
        double mean = calculateSMA(values);
        double sumSquaredDifferences = 0;

        for (double value : values) {
            sumSquaredDifferences += Math.pow(value - mean, 2);
        }

        double variance = sumSquaredDifferences / values.length;
        return Math.sqrt(variance);
    }


    private static double[] calculateFibonacciRetracementLevels(double[] highPrices, double[] lowPrices) {
        double[] fibonacciLevels = new double[5];

        int timeframe = FIBONACCI_PERIOD;
        if (highPrices.length < timeframe || lowPrices.length < timeframe) {
            return fibonacciLevels;
        }

        // Belirlenen zaman diliminin en yüksek ve en düşük fiyatlarını al
        double[] lastHighs = Arrays.copyOfRange(highPrices, highPrices.length - timeframe, highPrices.length);
        double[] lastLows = Arrays.copyOfRange(lowPrices, lowPrices.length - timeframe, lowPrices.length);

        double recentHigh = Arrays.stream(lastHighs).max().getAsDouble();
        double recentLow = Arrays.stream(lastLows).min().getAsDouble();

        // %23.6 seviyesi
        fibonacciLevels[0] = recentHigh - ((recentHigh - recentLow) * 0.236);

        // %38.2 seviyesi
        fibonacciLevels[1] = recentHigh - ((recentHigh - recentLow) * 0.382);

        // %50.0 seviyesi
        fibonacciLevels[2] = recentHigh - ((recentHigh - recentLow) * 0.5);

        // %61.8 seviyesi
        fibonacciLevels[3] = recentHigh - ((recentHigh - recentLow) * 0.618);

        // %100.0 seviyesi
        fibonacciLevels[4] = recentLow;

        return fibonacciLevels;
    }


    private static Map<String, Double> calculateADX(double[] highPrices, double[] lowPrices, double[] closingPrices, int period) {
        int length = closingPrices.length;
        double[] trueRange = new double[length];
        double[] positiveDM = new double[length];
        double[] negativeDM = new double[length];
        double[] trN = new double[length];
        double[] plusDMN = new double[length];
        double[] minusDMN = new double[length];
        double[] plusDIN = new double[length];
        double[] minusDIN = new double[length];
        double[] dx = new double[length];
        double[] adx = new double[length];

        for (int i = 1; i < length; i++) {
            double tr = Math.max(highPrices[i] - lowPrices[i],
                    Math.max(Math.abs(highPrices[i] - closingPrices[i - 1]),
                            Math.abs(lowPrices[i] - closingPrices[i - 1])));
            trueRange[i] = tr;

            double pdm = highPrices[i] - highPrices[i - 1];
            double ndm = lowPrices[i - 1] - lowPrices[i];
            positiveDM[i] = pdm > ndm && pdm > 0 ? pdm : 0;
            negativeDM[i] = ndm > pdm && ndm > 0 ? ndm : 0;
        }

        trN[period - 1] = Arrays.stream(Arrays.copyOfRange(trueRange, 1, period + 1)).sum();
        plusDMN[period - 1] = Arrays.stream(Arrays.copyOfRange(positiveDM, 1, period + 1)).sum();
        minusDMN[period - 1] = Arrays.stream(Arrays.copyOfRange(negativeDM, 1, period + 1)).sum();

        for (int i = period; i < length; i++) {
            trN[i] = trN[i - 1] - (trN[i - 1] / period) + trueRange[i];
            plusDMN[i] = plusDMN[i - 1] - (plusDMN[i - 1] / period) + positiveDM[i];
            minusDMN[i] = minusDMN[i - 1] - (minusDMN[i - 1] / period) + negativeDM[i];

            plusDIN[i] = 100 * plusDMN[i] / trN[i];
            minusDIN[i] = 100 * minusDMN[i] / trN[i];

            double diDiff = Math.abs(plusDIN[i] - minusDIN[i]);
            double diSum = plusDIN[i] + minusDIN[i];
            dx[i] = 100 * diDiff / diSum;
        }

        // Smoothed DX for ADX
        adx[2 * period - 2] = Arrays.stream(Arrays.copyOfRange(dx, period, 2 * period)).average().orElse(0);
        for (int i = 2 * period - 1; i < length; i++) {
            adx[i] = ((adx[i - 1] * (period - 1)) + dx[i]) / period;
        }

        Map<String, Double> result = new HashMap<>();
        result.put("ADX", adx[length - 1]);
        result.put("PlusDI", plusDIN[length - 1]);
        result.put("MinusDI", minusDIN[length - 1]);

        return result;
    }


    private static double[] calculateParabolicSAR(double[] highPrices, double[] lowPrices) {
        double[] sarValues = new double[highPrices.length];
        double accelerationFactor = 0.02; // Başlangıç hızlandırma faktörü
        double maxAccelerationFactor = 0.20; // Maksimum hızlandırma faktörü
        double accelerationIncrement = 0.02; // Hızlandırma faktörü artışı

        double sar = lowPrices[0]; // İlk SAR değeri, ilk verinin en düşük fiyatıyla başlar
        double extremePoint = highPrices[0]; // İlk ekstrem nokta, ilk verinin en yüksek fiyatıyla başlar
        boolean trendUp = true; // SAR trendi yukarıda başlar

        sarValues[0] = sar; // İlk SAR değeri

        for (int i = 1; i < highPrices.length; i++) {
            if (trendUp) {
                if (highPrices[i] > extremePoint) {
                    extremePoint = highPrices[i];
                    accelerationFactor += accelerationIncrement;
                    if (accelerationFactor > maxAccelerationFactor) {
                        accelerationFactor = maxAccelerationFactor;
                    }
                }

                sar += accelerationFactor * (extremePoint - sar);

                if (sar > lowPrices[i]) {
                    trendUp = false;
                    extremePoint = lowPrices[i];
                    accelerationFactor = 0.02; // Başlangıç hızlandırma faktörüne geri dön
                }
            } else {
                if (lowPrices[i] < extremePoint) {
                    extremePoint = lowPrices[i];
                    accelerationFactor += accelerationIncrement;
                    if (accelerationFactor > maxAccelerationFactor) {
                        accelerationFactor = maxAccelerationFactor;
                    }
                }

                sar += accelerationFactor * (extremePoint - sar);

                if (sar < highPrices[i]) {
                    trendUp = true;
                    extremePoint = highPrices[i];
                    accelerationFactor = 0.02; // Başlangıç hızlandırma faktörüne geri dön
                }
            }

            sarValues[i] = sar;
        }

        return sarValues;
    }


    private static void analyzeTrend(String symbol, String dateStr, double[] closingPrices, double[] highPrices, double[] lowPrices) {
        double rsi = calculateRSI_EMA(closingPrices);
        double sma = calculateSMA(closingPrices);
        double ema = calculateEMA(closingPrices);
        double[][] macdValues = calculateMACD(closingPrices);
        double currentMacd = macdValues[0][0];
        double currentSignal = macdValues[0][1];
        double[][] stochasticValues = calculateStochasticOscillator(highPrices, lowPrices, closingPrices);
        double stochasticK = stochasticValues[0][stochasticValues[0].length - 1];
        double stochasticD = stochasticValues[1][stochasticValues[1].length - 1];
        Map<String, Double> adxResult = calculateADX(highPrices, lowPrices, closingPrices, PERIOD);
        double adx = adxResult.get("ADX");
        double positiveDI = adxResult.get("PlusDI");
        double negativeDI = adxResult.get("MinusDI");
        double lastParabolicSAR = calculateParabolicSAR(highPrices, lowPrices)[closingPrices.length - 1];
        double[] fibonacciLevels = calculateFibonacciRetracementLevels(highPrices, lowPrices);
        double closestFibonacciLevel = getClosestFibonacciLevel(closingPrices[closingPrices.length - 1], fibonacciLevels);

        double[] bollingerBands = calculateBollingerBands(closingPrices);  // Tipik olarak 20 gün kullanılır.
        double upperBand = bollingerBands[1];
        double lowerBand = bollingerBands[2];


        // Trend belirleme
        String trendDirection = determineTrendDirection(rsi, ema, sma, currentMacd, currentSignal, adx, positiveDI, negativeDI, stochasticK, stochasticD, lastParabolicSAR, closingPrices, highPrices, lowPrices);

        double currentPrice = closingPrices[closingPrices.length - 1];  // Son fiyatı al

        if (!"Neutral/No Clear Signal".equals(trendDirection)
                && !"Mild Bullish Signal".equals(trendDirection)
                && !"Mild Bearish Signal".equals(trendDirection) && !"Sideways/Range-bound Market".equals(trendDirection)) {
            if ("Strong Bullish Signal".equals(trendDirection)
                    || "Strong Bearish Signal".equals(trendDirection)) {
                sendTelegramMessage(trendDirection, symbol, currentPrice,closestFibonacciLevel);
            }
        }

 /*       if (!"Neutral/No Clear Signal".equals(trendDirection)
                && !"Sideways/Range-bound Market".equals(trendDirection)) {
            if ("Strong Bullish Signal".equals(trendDirection)
                    || "Strong Bearish Signal".equals(trendDirection) || "Mild Bullish Signal".equals(trendDirection) ||
                    "Mild Bearish Signal".equals(trendDirection)) {
                sendTelegramMessage(trendDirection, symbol, currentPrice, closestFibonacciLevel);
            }
        }*/




    // Sonuçları yazdır
        System.out.println("-------------------------");
        System.out.println("Tarih: "+dateStr);
        System.out.println("Symbol: "+symbol);
        System.out.println("Güncel Fiyat: "+currentPrice);
        System.out.println("RSI: "+rsi);
        System.out.println("SMA: "+sma);
        System.out.println("EMA: "+ema);
        System.out.println("MACD: "+currentMacd);
        System.out.println("MACD Sinyali: "+currentSignal);
        System.out.println("Stokastik K: "+stochasticK);
        System.out.println("Stokastik D: "+stochasticD);
        System.out.println("ADX: "+adx);
        System.out.println("Parabolik SAR: "+lastParabolicSAR);
        System.out.println("En Yakın Fibonacci Seviyesi: "+closestFibonacciLevel); // Bu satırı ekledim.
        System.out.println("Üst Bollinger Bandı: "+upperBand);
        System.out.println("Alt Bollinger Bandı: "+lowerBand);
        System.out.println("Trend Yönü: "+trendDirection);
        System.out.println("-------------------------");
}

    private static double getClosestFibonacciLevel(double currentPrice, double[] fibonacciLevels) {
        double closestLevel = fibonacciLevels[0];
        double minDistance = Math.abs(currentPrice - fibonacciLevels[0]);

        for (int i = 1; i < fibonacciLevels.length; i++) {
            double distance = Math.abs(currentPrice - fibonacciLevels[i]);
            if (distance < minDistance) {
                minDistance = distance;
                closestLevel = fibonacciLevels[i];
            }
        }

        return closestLevel;
    }


    private static String determineTrendDirection(double rsi, double ema, double sma, double currentMacd, double currentSignal, double adx, double positiveDI, double negativeDI, double stochasticK, double stochasticD, double lastParabolicSAR, double[] closingPrices, double[] highPrices, double[] lowPrices) {

        // RSI İndikatörü
        boolean isOverbought = rsi > 70;
        boolean isOversold = rsi < 30;

        // MACD İndikatörü
        boolean bullishMACD = currentMacd > currentSignal;
        boolean bearishMACD = currentMacd < currentSignal;

        // ADX İndikatörü
        boolean strongTrend = adx > 25;
        boolean weakTrend = adx <= 20;
        boolean bullishTrendWithDI = positiveDI > negativeDI && strongTrend;
        boolean bearishTrendWithDI = negativeDI > positiveDI && strongTrend;

        // Stokastik Osilatör
        boolean stochasticOversold = stochasticK < 20 && stochasticD < 20;
        boolean stochasticOverbought = stochasticK > 80 && stochasticD > 80;

        // Parabolik SAR
        boolean bullishSAR = lastParabolicSAR < closingPrices[closingPrices.length - 1];
        boolean bearishSAR = lastParabolicSAR > closingPrices[closingPrices.length - 1];

        // MA (Moving Averages)
        boolean priceAboveEMA = closingPrices[closingPrices.length - 1] > ema;
        boolean priceBelowEMA = closingPrices[closingPrices.length - 1] < ema;

        // SMA (Simple Moving Average)
        boolean priceAboveSMA = closingPrices[closingPrices.length - 1] > sma;
        boolean priceBelowSMA = closingPrices[closingPrices.length - 1] < sma;

        // Kombinasyonlar
        double[] bollingerBands = calculateBollingerBands(closingPrices);
        double upperBollingerBand = bollingerBands[1];
        double lowerBollingerBand = bollingerBands[2];

        double[] fibonacciLevels = calculateFibonacciRetracementLevels(highPrices, lowPrices);
        double closestFibonacciLevel = getClosestFibonacciLevel(closingPrices[closingPrices.length - 1], fibonacciLevels);

        boolean isNearUpperBollingerBand = closingPrices[closingPrices.length - 1] > 0.98 * upperBollingerBand;
        boolean isNearLowerBollingerBand = closingPrices[closingPrices.length - 1] < 1.02 * lowerBollingerBand;
        boolean isNearFibonacciLevel = Math.abs(closingPrices[closingPrices.length - 1] - closestFibonacciLevel) < 0.02 * closestFibonacciLevel;

        // Kombinasyonlar
        /*if (isOversold && bullishMACD && bullishSAR && bullishTrendWithDI && stochasticOversold && priceAboveEMA && priceAboveSMA && isNearLowerBollingerBand && isNearFibonacciLevel) {*/
        if (isOversold && bullishMACD && bullishSAR && bullishTrendWithDI && stochasticOversold && priceAboveEMA && priceAboveSMA) {
        return "Strong Bullish Signal";
        /*} else if (isOverbought && bearishMACD && bearishSAR && bearishTrendWithDI && stochasticOverbought && priceBelowEMA && priceBelowSMA && isNearUpperBollingerBand && isNearFibonacciLevel) {*/
        } else if (isOverbought && bearishMACD && bearishSAR && bearishTrendWithDI && stochasticOverbought && priceBelowEMA && priceBelowSMA) {
            return "Strong Bearish Signal";
        } else if (bullishMACD && bullishSAR && bullishTrendWithDI && priceAboveEMA && priceAboveSMA) {
            return "Mild Bullish Signal";
        } else if (bearishMACD && bearishSAR && bearishTrendWithDI && priceBelowEMA && priceBelowSMA) {
            return "Mild Bearish Signal";
        } else if (weakTrend) {
            return "Sideways/Range-bound Market";
        } else {
            return "Neutral/No Clear Signal";
        }
    }


    // Function to send a message to a Telegram bot
    private static void sendTelegramMessage(String trendDirection, String symbol, double currentPrice, double closestFibonacciLevel) {
        String message = "";

        switch (trendDirection) {
            case "Strong Bullish Signal":
                message = "🟢 <b>Alım Sinyali</b>: " + symbol + " - Güncel Fiyat: " + currentPrice + " - En Yakın Fibonacci Seviyesi: " + closestFibonacciLevel;
                break;
            case "Strong Bearish Signal":
                message = "🔴 <b>Satım Sinyali</b>: " + symbol + " - Güncel Fiyat: " + currentPrice + " - En Yakın Fibonacci Seviyesi: " + closestFibonacciLevel;
                break;
            case "Mild Bullish Signal":
                message = "🟡 <b>Zayıf Alım Sinyali</b>: " + symbol + " - Güncel Fiyat: " + currentPrice + " - En Yakın Fibonacci Seviyesi: " + closestFibonacciLevel;
                break;
            case "Mild Bearish Signal":
                message = "🔶 <b>Zayıf Satım Sinyali</b>: " + symbol + " - Güncel Fiyat: " + currentPrice + " - En Yakın Fibonacci Seviyesi: " + closestFibonacciLevel;
                break;
            case "Sideways/Range-bound Market":
                message = "🔵 <b>Yatay Piyasa</b>: " + symbol + " - Güncel Fiyat: " + currentPrice + " - En Yakın Fibonacci Seviyesi: " + closestFibonacciLevel;
                break;
            default:
                message = "🔍 <b>Net Bir Sinyal Yok</b>: " + symbol + " - Güncel Fiyat: " + currentPrice + " - En Yakın Fibonacci Seviyesi: " + closestFibonacciLevel;
        }

        try {
            String botToken = "6482508265:AAEDUmyCM-ygU7BVO-txyykS7cKn5URspmY";  // Replace with your actual bot token
            long chatId = 1692398446;           // Replace with your actual chat ID

            String urlString = "https://api.telegram.org/bot" + botToken + "/sendMessage?chat_id=" + chatId + "&text=" + message + "&parse_mode=HTML";
            URL url = new URL(urlString);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");

            int responseCode = con.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                // Message sent successfully
                System.out.println("Telegram message sent: " + message);
            } else {
                // Handle error
                System.err.println("Error sending Telegram message. Response code: " + responseCode);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static double getCoinPrice(String symbol) {
        try {
            String apiUrl = "https://api.binance.com/api/v3/ticker/price?symbol=" + symbol;
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

            JSONObject jsonResponse = new JSONObject(response.toString());
            return Double.parseDouble(jsonResponse.getString("price"));
        } catch (Exception e) {
            e.printStackTrace();
            return -1; // Hata durumunda -1 dönebilirsiniz.
        }
    }

}
