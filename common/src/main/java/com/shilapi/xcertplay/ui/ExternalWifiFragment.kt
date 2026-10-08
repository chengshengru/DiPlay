package com.shilapi.xcertplay.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.host.databinding.FragmentExternalWifiBinding
import com.shilapi.xcertplay.network.DiscoveredDevice
import com.shilapi.xcertplay.network.ExternalWifiConnectionManager

/**
 * 外部 Wi-Fi 连接界面（迁移指南第 12 章新功能）。
 *
 * 提供 mDNS 设备扫描、手动 IP 连接、断开与状态显示。
 * API 17 迁移：使用 View 体系 + ViewBinding（Compose 要求 API 21+）。
 *
 * @author TRAE AI
 * @since 2026-10-08
 */
class ExternalWifiFragment : Fragment() {

    private var _binding: FragmentExternalWifiBinding? = null
    private val binding get() = _binding!!

    private lateinit var connectionManager: ExternalWifiConnectionManager
    private val deviceList = mutableListOf<DiscoveredDevice>()
    private lateinit var deviceAdapter: ArrayAdapter<String>

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentExternalWifiBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        connectionManager = ExternalWifiConnectionManager(requireContext().applicationContext)

        setupUI()
        setupConnectionListener()
        updateWifiInfo()
    }

    override fun onDestroyView() {
        // 释放连接与监听，避免 Fragment 销毁后回调操作已失效的 binding
        connectionManager.release()
        _binding = null
        super.onDestroyView()
    }

    /** 初始化设备列表与按钮交互。 */
    private fun setupUI() {
        deviceAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1)
        binding.lvDevices.adapter = deviceAdapter

        // 点击设备连接
        binding.lvDevices.setOnItemClickListener { _, _, position, _ ->
            if (position < deviceList.size) {
                connectionManager.connectToDevice(deviceList[position])
            }
        }

        // 扫描按钮
        binding.btnScan.setOnClickListener { startScan() }

        // 手动连接按钮
        binding.btnConnect.setOnClickListener {
            val ip = binding.etIpAddress.text.toString().trim()
            if (ip.isNotEmpty()) {
                connectionManager.connectToDevice(ip)
            } else {
                toast(getString(R.string.settings_external_wifi_input_ip))
            }
        }

        // 断开按钮
        binding.btnDisconnect.setOnClickListener { connectionManager.disconnect() }
    }

    /** 注册连接状态回调。 */
    private fun setupConnectionListener() {
        connectionManager.setConnectionListener(object :
            ExternalWifiConnectionManager.ExternalWifiConnectionListener {

            override fun onStateChanged(state: ExternalWifiConnectionManager.ConnectionState) {
                if (_binding == null) return
                updateUIForState(state)
            }

            override fun onDeviceDiscovered(device: DiscoveredDevice) {
                if (_binding == null) return
                deviceList.add(device)
                refreshDeviceList()
                updateStatus(getString(R.string.settings_external_wifi_found_device, device.name))
            }

            override fun onConnected(device: DiscoveredDevice) {
                if (_binding == null) return
                updateStatus(getString(R.string.settings_external_wifi_connected, device.name))
                toast(getString(R.string.settings_external_wifi_connected, device.name))
            }

            override fun onDisconnected(reason: String) {
                if (_binding == null) return
                updateStatus(getString(R.string.settings_external_wifi_disconnected, reason))
            }

            override fun onError(error: Exception) {
                if (_binding == null) return
                updateStatus(getString(R.string.settings_external_wifi_error, error.message ?: ""))
                toast(error.message ?: error.javaClass.simpleName)
            }
        })
    }

    /** 开始 mDNS 扫描。 */
    private fun startScan() {
        if (!connectionManager.isExternalWifiAvailable()) {
            toast(getString(R.string.settings_external_wifi_not_available))
            return
        }

        deviceList.clear()
        refreshDeviceList()
        updateStatus(getString(R.string.settings_external_wifi_scanning))
        binding.progressBar.visibility = View.VISIBLE
        binding.btnScan.isEnabled = false

        connectionManager.startAutoDiscovery()
    }

    /** 刷新设备列表显示。 */
    private fun refreshDeviceList() {
        deviceAdapter.clear()
        deviceAdapter.addAll(deviceList.map { device ->
            getString(R.string.settings_external_wifi_device_label, device.name, device.host ?: "", device.port)
        })
    }

    /** 显示当前 Wi-Fi 信息。 */
    private fun updateWifiInfo() {
        val wifiInfo = connectionManager.getWifiInfo()
        if (wifiInfo != null) {
            binding.tvSsid.text = "SSID: ${wifiInfo.ssid}"
            binding.tvIpAddress.text = "IP: ${wifiInfo.getIpAddressString()}"
            binding.tvFrequency.text = if (wifiInfo.is5GHz()) {
                "Band: 5GHz (recommended)"
            } else {
                "Band: 2.4GHz"
            }
        } else {
            binding.tvSsid.text = getString(R.string.settings_external_wifi_ssid_unknown)
            binding.tvIpAddress.text = getString(R.string.settings_external_wifi_ip_unknown)
            binding.tvFrequency.text = getString(R.string.settings_external_wifi_band_unknown)
        }
    }

    /** 按连接状态调整控件可用性。 */
    private fun updateUIForState(state: ExternalWifiConnectionManager.ConnectionState) {
        binding.progressBar.visibility = when (state) {
            ExternalWifiConnectionManager.ConnectionState.DISCOVERING,
            ExternalWifiConnectionManager.ConnectionState.CONNECTING -> View.VISIBLE
            else -> View.GONE
        }

        binding.btnScan.isEnabled = state != ExternalWifiConnectionManager.ConnectionState.DISCOVERING
        binding.btnDisconnect.isEnabled = state == ExternalWifiConnectionManager.ConnectionState.CONNECTED
    }

    private fun updateStatus(message: String) {
        binding.tvStatus.text = message
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
}
