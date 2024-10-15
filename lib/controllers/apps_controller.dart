import 'package:get/get.dart';
import 'package:installed_apps/app_info.dart';
import 'package:installed_apps/installed_apps.dart';

class AppsController extends GetxController {
  var apps = <AppInfo>[].obs;
  var loading = false.obs;
  var firstLoad = true.obs;
  var selectedApps = <String>{}.obs;

  void refreshApps() async {
    loading.value = true;
    apps.value = await InstalledApps.getInstalledApps(false, true, "");
    for (var app in selectedApps) {
      if (!apps.any((element) => element.packageName == app)) {
        selectedApps.remove(app);
      }
    }
    loading.value = false;
    firstLoad.value = false;
  }
}
