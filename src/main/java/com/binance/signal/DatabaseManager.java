package com.binance.signal;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Date;

public class DatabaseManager {
    private static final String DB_URL = "jdbc:sqlite:market_data.db";
    private Connection connection;

    public DatabaseManager() throws SQLException {
        connection = DriverManager.getConnection(DB_URL);
        initializeDatabase();
    }

    private void initializeDatabase() throws SQLException {
        String createTableSQL = "CREATE TABLE IF NOT EXISTS kline_data (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "symbol TEXT, " +
                "timestamp INTEGER, " +
                "open REAL, " +
                "high REAL, " +
                "low REAL, " +
                "close REAL, " +
                "volume REAL" +
                ");";
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createTableSQL);
        }
    }

    public void storeKlineData(String symbol, List<Date> dates, double[] openPrices, double[] highPrices,
                               double[] lowPrices, double[] closePrices, double[] volume) throws SQLException {
        String insertSQL = "INSERT INTO kline_data(symbol, timestamp, open, high, low, close, volume) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(insertSQL)) {
            for (int i = 0; i < dates.size(); i++) {
                pstmt.setString(1, symbol);
                pstmt.setLong(2, dates.get(i).getTime());
                pstmt.setDouble(3, openPrices[i]);
                pstmt.setDouble(4, highPrices[i]);
                pstmt.setDouble(5, lowPrices[i]);
                pstmt.setDouble(6, closePrices[i]);
                pstmt.setDouble(7, volume[i]);
                pstmt.addBatch();
            }
            pstmt.executeBatch();
        }
    }

    public List<MarketDataRecord> getHistoricalData(String symbol, long startTimestamp, long endTimestamp) throws SQLException {
        List<MarketDataRecord> records = new ArrayList<>();
        String sql = "SELECT * FROM kline_data WHERE symbol = ? AND timestamp BETWEEN ? AND ? ORDER BY timestamp ASC";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, symbol);
            pstmt.setLong(2, startTimestamp);
            pstmt.setLong(3, endTimestamp);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                MarketDataRecord record = new MarketDataRecord(
                        new Date(rs.getLong("timestamp")),
                        rs.getDouble("open"),
                        rs.getDouble("high"),
                        rs.getDouble("low"),
                        rs.getDouble("close"),
                        rs.getDouble("volume")
                );
                records.add(record);
            }
        }
        return records;
    }

    public void close() throws SQLException {
        if (connection != null) connection.close();
    }
}
