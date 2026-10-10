package main

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/electricbubble/gadb"
	wailsruntime "github.com/wailsapp/wails/v2/pkg/runtime"
)

// App struct
type App struct {
	ctx            context.Context
	adbClient      gadb.Client
	selectedDevice *gadb.Device
	adbPath        string // 儲存 adb 路徑以便關閉時使用
}

// DeviceInfo 裝置資訊結構
type DeviceInfo struct {
	Serial  string `json:"serial"`
	State   string `json:"state"`
	Product string `json:"product"`
	Model   string `json:"model"`
}

// AppStatus 應用程式狀態結構
type AppStatus struct {
	Shizuku bool `json:"shizuku"`
	Nrfr    struct {
		Installed  bool `json:"installed"`
		NeedUpdate bool `json:"needUpdate"`
	} `json:"nrfr"`
}

// NewApp creates a new App application struct
func NewApp() *App {
	return &App{}
}

// startup is called when the app starts. The context is saved
func (a *App) startup(ctx context.Context) {
	a.ctx = ctx

	// 嘗試啟動 ADB 伺服器
	execPath, err := os.Executable()
	if err != nil {
		wailsruntime.LogError(ctx, fmt.Sprintf("取得執行路徑失敗: %v", err))
		return
	}
	execDir := filepath.Dir(execPath)

	// 優先使用程式目錄下的 platform-tools
	adbPath := filepath.Join(execDir, "platform-tools", "adb")
	if runtime.GOOS == "windows" {
		adbPath = filepath.Join(execDir, "platform-tools", "adb.exe")
	}

	// 如果程式目錄下的 adb 不存在，嘗試使用系統環境中的 adb
	if _, err := os.Stat(adbPath); os.IsNotExist(err) {
		if runtime.GOOS == "windows" {
			// 嘗試從環境變量中取得 ANDROID_HOME
			androidHome := os.Getenv("ANDROID_HOME")
			if androidHome == "" {
				// 如果沒有環境變量，嘗試從預設安裝路徑取得
				androidHome = filepath.Join(os.Getenv("LOCALAPPDATA"), "Android", "Sdk")
			}
			adbPath = filepath.Join(androidHome, "platform-tools", "adb.exe")
		} else {
			adbPath = "adb" // 在類 Unix 系統上使用 PATH 中的 adb
		}
	}

	// 儲存 adb 路徑
	a.adbPath = adbPath

	// 檢查 ADB 伺服器是否已在執行
	cmd := exec.Command(adbPath, "devices")
	if runtime.GOOS == "windows" {
		cmd.SysProcAttr = &syscall.SysProcAttr{
			HideWindow:    true,
			CreationFlags: 0x08000000, // CREATE_NO_WINDOW
		}
	}
	if err := cmd.Run(); err != nil {
		// ADB 伺服器未執行，啟動它
		startCmd := exec.Command(adbPath, "start-server")
		if runtime.GOOS == "windows" {
			startCmd.SysProcAttr = &syscall.SysProcAttr{
				HideWindow:    true,
				CreationFlags: 0x08000000, // CREATE_NO_WINDOW
			}
		}
		if err := startCmd.Run(); err != nil {
			wailsruntime.LogError(ctx, fmt.Sprintf("啟動ADB伺服器失敗: %v", err))
		}
	}

	// 初始化 ADB 客戶端
	client, err := gadb.NewClient()
	if err != nil {
		wailsruntime.LogError(ctx, fmt.Sprintf("初始化ADB失敗: %v", err))
		return
	}
	a.adbClient = client
}

// shutdown is called when the app is closing
func (a *App) shutdown(ctx context.Context) {
	// 關閉 ADB 伺服器
	if a.adbPath != "" {
		cmd := exec.Command(a.adbPath, "kill-server")
		if runtime.GOOS == "windows" {
			cmd.SysProcAttr = &syscall.SysProcAttr{
				HideWindow:    true,
				CreationFlags: 0x08000000, // CREATE_NO_WINDOW
			}
		}
		if err := cmd.Run(); err != nil {
			wailsruntime.LogError(ctx, fmt.Sprintf("關閉ADB伺服器失敗: %v", err))
		}
	}
}

// GetDevices 取得已連接的裝置列表
func (a *App) GetDevices() []DeviceInfo {
	devices, err := a.adbClient.DeviceList()
	if err != nil {
		wailsruntime.LogError(a.ctx, fmt.Sprintf("取得裝置列表失敗: %v", err))
		return nil
	}

	var deviceInfos []DeviceInfo
	for _, device := range devices {
		state, _ := device.State()
		product, _ := device.Product()
		model, _ := device.Model()

		info := DeviceInfo{
			Serial:  device.Serial(),
			State:   string(state),
			Product: product,
			Model:   model,
		}
		deviceInfos = append(deviceInfos, info)
	}
	return deviceInfos
}

// SelectDevice 選擇裝置
func (a *App) SelectDevice(serial string) error {
	devices, err := a.adbClient.DeviceList()
	if err != nil {
		return fmt.Errorf("取得裝置列表失敗: %v", err)
	}

	for _, device := range devices {
		if device.Serial() == serial {
			a.selectedDevice = &device
			return nil
		}
	}
	return fmt.Errorf("未找到裝置: %s", serial)
}

// CheckApps 檢查必要的應用程式是否已安裝
func (a *App) CheckApps() AppStatus {
	if a.selectedDevice == nil {
		return AppStatus{
			Shizuku: false,
			Nrfr: struct {
				Installed  bool `json:"installed"`
				NeedUpdate bool `json:"needUpdate"`
			}{
				Installed:  false,
				NeedUpdate: false,
			},
		}
	}

	// 檢查 Shizuku
	shizukuInstalled, _ := a.isPackageInstalled("moe.shizuku.privileged.api")

	// 檢查 Nrfr
	nrfrInstalled, _ := a.isPackageInstalled("com.github.nrfr")
	needUpdate := false
	if nrfrInstalled {
		needUpdate, _ = a.CheckNrfrUpdate()
	}

	return AppStatus{
		Shizuku: shizukuInstalled,
		Nrfr: struct {
			Installed  bool `json:"installed"`
			NeedUpdate bool `json:"needUpdate"`
		}{
			Installed:  nrfrInstalled,
			NeedUpdate: needUpdate,
		},
	}
}

// isPackageInstalled 檢查包是否已安裝
func (a *App) isPackageInstalled(packageName string) (bool, error) {
	output, err := a.selectedDevice.RunShellCommand("pm", "list", "packages", packageName)
	if err != nil {
		return false, err
	}
	return strings.Contains(output, packageName), nil
}

// InstallShizuku 安裝 Shizuku
func (a *App) InstallShizuku() error {
	if a.selectedDevice == nil {
		return fmt.Errorf("未選擇裝置")
	}

	// 從本地資源目錄推送 APK 到裝置
	execPath, err := os.Executable()
	if err != nil {
		return fmt.Errorf("取得執行路徑失敗: %v", err)
	}
	execDir := filepath.Dir(execPath)
	localApk := filepath.Join(execDir, "resources", "shizuku.apk")
	remoteApk := "/data/local/tmp/shizuku.apk"

	// 檢查檔案是否存在
	if _, err := os.Stat(localApk); os.IsNotExist(err) {
		return fmt.Errorf("shizuku apk 檔案不存在: %s", localApk)
	}

	// 推送 APK 檔案
	file, err := os.Open(localApk)
	if err != nil {
		return fmt.Errorf("打開 Shizuku APK 檔案失敗: %v", err)
	}
	defer file.Close()

	err = a.selectedDevice.Push(file, remoteApk, time.Now())
	if err != nil {
		return fmt.Errorf("推送 Shizuku APK 檔案失敗: %v", err)
	}

	// 安裝 APK
	_, err = a.selectedDevice.RunShellCommand("pm", "install", "-r", remoteApk)
	if err != nil {
		return fmt.Errorf("安裝 Shizuku 失敗: %v", err)
	}

	// 清理暫時檔案
	_, err = a.selectedDevice.RunShellCommand("rm", remoteApk)
	if err != nil {
		wailsruntime.LogWarning(a.ctx, fmt.Sprintf("清理暫時檔案失敗: %v", err))
	}

	return nil
}

// InstallNrfr 安裝 Nrfr
func (a *App) InstallNrfr() error {
	if a.selectedDevice == nil {
		return fmt.Errorf("未選擇裝置")
	}

	// 從本地資源目錄推送 APK 到裝置
	execPath, err := os.Executable()
	if err != nil {
		return fmt.Errorf("取得執行路徑失敗: %v", err)
	}
	execDir := filepath.Dir(execPath)
	localApk := filepath.Join(execDir, "resources", "nrfr.apk")
	remoteApk := "/data/local/tmp/nrfr.apk"

	// 檢查檔案是否存在
	if _, err := os.Stat(localApk); os.IsNotExist(err) {
		return fmt.Errorf("nrfr apk 檔案不存在: %s", localApk)
	}

	// 推送 APK 檔案
	file, err := os.Open(localApk)
	if err != nil {
		return fmt.Errorf("打開 Nrfr APK 檔案失敗: %v", err)
	}
	defer file.Close()

	err = a.selectedDevice.Push(file, remoteApk, time.Now())
	if err != nil {
		return fmt.Errorf("推送 Nrfr APK 檔案失敗: %v", err)
	}

	// 安裝 APK
	_, err = a.selectedDevice.RunShellCommand("pm", "install", "-r", remoteApk)
	if err != nil {
		return fmt.Errorf("安裝 Nrfr 失敗: %v", err)
	}

	// 清理暫時檔案
	_, err = a.selectedDevice.RunShellCommand("rm", remoteApk)
	if err != nil {
		wailsruntime.LogWarning(a.ctx, fmt.Sprintf("清理暫時檔案失敗: %v", err))
	}

	return nil
}

// StartShizuku 啟動 Shizuku
func (a *App) StartShizuku() error {
	if a.selectedDevice == nil {
		return fmt.Errorf("未選擇裝置")
	}

	// 先啟動 Shizuku 應用程式
	_, err := a.selectedDevice.RunShellCommand("monkey", "-p", "moe.shizuku.privileged.api", "1")
	if err != nil {
		return fmt.Errorf("啟動 shizuku 應用程式失敗: %v", err)
	}

	// 等待應用程式啟動
	time.Sleep(time.Second * 2)

	// 執行啟動腳本
	output, err := a.selectedDevice.RunShellCommand("sh", "/sdcard/Android/data/moe.shizuku.privileged.api/start.sh")
	if err != nil {
		return fmt.Errorf("啟動 shizuku 服務失敗: %v", err)
	}
	wailsruntime.LogInfo(a.ctx, fmt.Sprintf("shizuku 啟動輸出: %s", output))
	return nil
}

// WindowMinimise 最小化視窗
func (a *App) WindowMinimise() {
	wailsruntime.WindowMinimise(a.ctx)
}

// WindowMaximise 最大化視窗
func (a *App) WindowMaximise() {
	wailsruntime.WindowToggleMaximise(a.ctx)
}

// WindowClose 關閉視窗
func (a *App) WindowClose() {
	// 先關閉 ADB 伺服器
	if a.adbPath != "" {
		cmd := exec.Command(a.adbPath, "kill-server")
		if runtime.GOOS == "windows" {
			cmd.SysProcAttr = &syscall.SysProcAttr{
				HideWindow:    true,
				CreationFlags: 0x08000000, // CREATE_NO_WINDOW
			}
		}
		_ = cmd.Run() // 忽略錯誤，因為我們即將退出程式
	}
	wailsruntime.Quit(a.ctx)
}

// StartNrfr 啟動 Nrfr 應用程式
func (a *App) StartNrfr() error {
	if a.selectedDevice == nil {
		return fmt.Errorf("未選擇裝置")
	}

	// 使用 monkey 啟動 Nrfr
	_, err := a.selectedDevice.RunShellCommand("monkey", "-p", "com.github.nrfr", "1")
	if err != nil {
		return fmt.Errorf("啟動 Nrfr 失敗: %v", err)
	}

	return nil
}

// GetAppVersion 取得已安裝應用程式的版本號
func (a *App) GetAppVersion(packageName string) (string, error) {
	if a.selectedDevice == nil {
		return "", fmt.Errorf("未選擇裝置")
	}

	output, err := a.selectedDevice.RunShellCommand("dumpsys", "package", packageName, "|", "grep", "versionName")
	if err != nil {
		return "", fmt.Errorf("取得版本號失敗: %v", err)
	}

	// 解析版本號
	parts := strings.Split(strings.TrimSpace(output), "=")
	if len(parts) != 2 {
		return "", fmt.Errorf("解析版本號失敗")
	}
	return strings.TrimSpace(parts[1]), nil
}

// compareVersions 比較兩個版本號，如果 v1 < v2 返回 -1，v1 = v2 返回 0，v1 > v2 返回 1
func compareVersions(v1, v2 string) int {
	// 移除可能的 'v' 前綴
	v1 = strings.TrimPrefix(v1, "v")
	v2 = strings.TrimPrefix(v2, "v")

	// 分割版本號
	parts1 := strings.Split(v1, ".")
	parts2 := strings.Split(v2, ".")

	// 確保兩個版本號都有三個部分
	for len(parts1) < 3 {
		parts1 = append(parts1, "0")
	}
	for len(parts2) < 3 {
		parts2 = append(parts2, "0")
	}

	// 比較每個部分
	for i := 0; i < 3; i++ {
		num1, _ := strconv.Atoi(parts1[i])
		num2, _ := strconv.Atoi(parts2[i])

		if num1 < num2 {
			return -1
		}
		if num1 > num2 {
			return 1
		}
	}

	return 0
}

// CheckNrfrUpdate 檢查Nrfr是否需要更新
func (a *App) CheckNrfrUpdate() (bool, error) {
	if a.selectedDevice == nil {
		return false, fmt.Errorf("未選擇裝置")
	}

	// 檢查是否已安裝
	installed, err := a.isPackageInstalled("com.github.nrfr")
	if err != nil {
		return false, err
	}

	if !installed {
		return true, nil // 未安裝，需要安裝
	}

	// 取得已安裝版本
	currentVersion, err := a.GetAppVersion("com.github.nrfr")
	if err != nil {
		return false, err
	}

	// 最新版本號（從build.gradle.kts中取得）
	latestVersion := "1.1.1" // 目前最新版本

	// 比較版本號
	return compareVersions(currentVersion, latestVersion) < 0, nil
}
