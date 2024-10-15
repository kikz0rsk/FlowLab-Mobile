class Utils {
  static String formatSize(int bytes) {
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    int unitIndex = 0;
    double sizeDouble = bytes.toDouble();
    while (sizeDouble >= 1024 && unitIndex < units.length - 1) {
      sizeDouble /= 1024;
      unitIndex++;
    }

    return '${sizeDouble.toStringAsFixed(2)} ${units[unitIndex]}';
  }
}