import 'dart:async';

import 'package:dp/pigeon/messages.g.dart';
import 'package:get/get.dart';

class VpnStatusController extends GetxController {
  Rx<VpnActiveStatus> vpnActive = Rx(VpnActiveStatus.INACTIVE);
  Timer? refreshTimer;
}
