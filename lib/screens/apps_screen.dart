import 'package:flutter/material.dart';

import 'package:dp/controllers/apps_controller.dart';
import 'package:get/get.dart';
import 'package:installed_apps/app_info.dart';

class AppsScreen extends StatelessWidget {
  final AppsController appsController = Get.find();

  AppsScreen({super.key}) {
    if (appsController.firstLoad.value) {
      appsController.refreshApps();
    }
  }

  void onChanged(AppInfo app, bool value) {
    if (value) {
      appsController.selectedApps.add(app.packageName);
    } else {
      appsController.selectedApps.remove(app.packageName);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
        appBar: AppBar(
          backgroundColor: Theme.of(context).colorScheme.inversePrimary,
          title: Text("Apps"),
          actions: [
            IconButton(
              icon: const Icon(Icons.refresh),
              onPressed: () {
                appsController.refreshApps();
              },
            ),
          ],
        ),
        body: Obx(
          () => appsController.loading.value
              ? const Center(child: CircularProgressIndicator())
              : ListView.builder(
                  itemCount: appsController.apps.length,
                  itemBuilder: (context, index) {
                    return ListTile(
                      onTap: () {
                        onChanged(appsController.apps[index],
                            !appsController.selectedApps.contains(appsController.apps[index].packageName));
                      },
                      title: Text(appsController.apps[index].name),
                      subtitle: Text('${appsController.apps[index].packageName} ${appsController.apps[index].versionName}'),
                      leading: appsController.apps[index].icon == null
                          ? const Placeholder()
                          : Image.memory(appsController.apps[index].icon!),
                      trailing: Obx(
                          () => Checkbox(
                            value: appsController.selectedApps
                                .contains(appsController.apps[index].packageName),
                            onChanged: (bool? val) async {
                              onChanged(appsController.apps[index], val!);
                            }),
                      ),
                    );
                  },
                ),
        ));
  }
}
