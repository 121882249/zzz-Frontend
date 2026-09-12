; Requires Inno Setup 6.7.1+. Original Inno Setup copyright notices are retained.
#if VER < EncodeVer(6, 7, 1)
  #error This installer requires Inno Setup 6.7.1 or later
#endif
#ifndef AppImageDir
  #error AppImageDir must point to the jpackage app-image
#endif
#ifndef OutputDirPath
  #error OutputDirPath must point to the packaging output directory
#endif
#define AppVersion "1.2.65"
#define RepoRoot AddBackslash(SourcePath) + "..\..\.."

[Setup]
AppId={{55841B51-BA2D-4BC4-BB9F-8AD99606334D}
AppName=TokenPro
AppVersion={#AppVersion}
AppVerName=TokenPro {#AppVersion}
AppPublisher=TokenPro
AppPublisherURL=https://tokenpro.work
AppSupportURL=https://tokenpro.work
AppUpdatesURL=https://tokenpro.work
VersionInfoDescription=TokenPro · AI 模型接入与用量管理
VersionInfoProductName=TokenPro
VersionInfoProductTextVersion={#AppVersion}
DefaultDirName={localappdata}\Programs\TokenPro
UsePreviousAppDir=no
DefaultGroupName=TokenPro
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
UninstallDisplayName=TokenPro · AI 模型接入
UninstallDisplayIcon={app}\TokenPro.exe
SetupIconFile={#RepoRoot}\Router.ico
OutputDir={#OutputDirPath}
OutputBaseFilename=TokenPro-{#AppVersion}-Windows-x64
Compression=lzma2
SolidCompression=yes
WizardStyle=modern dark polar includetitlebar
WizardSizePercent=120
WizardBackColor=#080D24
WizardBackImageFile=cosmos-installer-v1.png
WizardBackImageOpacity=225
WizardImageFile=
WizardSmallImageFile={#RepoRoot}\Resources\TokenProCosmosIcon.png
DisableWelcomePage=no
DisableDirPage=no
DisableProgramGroupPage=yes
DisableReadyPage=yes
ShowLanguageDialog=no
LanguageDetectionMethod=none
CloseApplications=no
RestartApplications=no
AllowNoIcons=yes
SetupLogging=yes

[Languages]
Name: "zh_CN"; MessagesFile: "ChineseSimplified.isl"

[Messages]
SetupWindowTitle=TokenPro · 安装
WelcomeLabel1=连接每一颗 AI 星辰
WelcomeLabel2=欢迎使用 TokenPro%n%n一个入口，连接主流 AI 模型。%n管理客户端与命令行连接，查看用量与余额。%n%n仅为当前用户安装，无需管理员权限。%n安装不会清除已有账号、模型配置和聊天记录。
SelectDirLabel3=为 TokenPro 选择安装位置
SelectDirBrowseLabel=推荐保留当前用户目录，方便后续免管理员增量更新。
InstallingLabel=正在准备你的 AI 模型控制中心，请稍候。
FinishedHeadingLabel=你的模型宇宙，准备就绪
FinishedLabel=TokenPro 已安装完成。%n%n打开应用并登录账号，即可选择模型、连接客户端。后续请在应用内点击“检查更新”。
ButtonBack=上一步
ButtonNext=下一步
ButtonInstall=开始安装
ButtonFinish=完成
ButtonCancel=取消

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "快捷入口："

[Files]
Source: "{#AppImageDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{userprograms}\TokenPro"; Filename: "{app}\TokenPro.exe"; WorkingDir: "{app}"; Comment: "TokenPro"
Name: "{userdesktop}\TokenPro"; Filename: "{app}\TokenPro.exe"; WorkingDir: "{app}"; Tasks: desktopicon; Comment: "TokenPro"

[Run]
Filename: "{app}\TokenPro.exe"; Description: "立即开启 TokenPro"; WorkingDir: "{app}"; Flags: nowait postinstall skipifsilent

[Code]
var BrandFooter: TNewStaticText;

procedure InitializeWizard;
begin
  WizardForm.Font.Name := 'Microsoft YaHei UI';
  WizardForm.WelcomeLabel1.Font.Name := 'Microsoft YaHei UI';
  WizardForm.WelcomeLabel1.Font.Size := 23;
  WizardForm.WelcomeLabel1.Font.Color := $FFE2BA;
  WizardForm.WelcomeLabel2.Font.Size := 11;
  WizardForm.FinishedHeadingLabel.Font.Name := 'Microsoft YaHei UI';
  WizardForm.FinishedHeadingLabel.Font.Size := 21;
  BrandFooter := TNewStaticText.Create(WizardForm);
  BrandFooter.Parent := WizardForm;
  BrandFooter.Left := ScaleX(18);
  BrandFooter.Top := WizardForm.NextButton.Top + ScaleY(6);
  BrandFooter.Caption := 'TokenPro  ·  连接模型宇宙';
  BrandFooter.Font.Name := 'Microsoft YaHei UI';
  BrandFooter.Font.Size := 9;
  BrandFooter.Font.Color := $E6D19C;
end;

function WithinDirectory(const Candidate, Root: String): Boolean;
begin
  Result := Pos(AddBackslash(Lowercase(ExpandFileName(Root))),
    AddBackslash(Lowercase(ExpandFileName(Candidate)))) = 1;
end;

function NextButtonClick(CurPageID: Integer): Boolean;
var Target: String;
begin
  Result := True;
  if CurPageID <> wpSelectDir then Exit;
  Target := ExpandFileName(WizardDirValue);
  if WithinDirectory(Target, ExpandConstant('{commonpf32}')) or
     WithinDirectory(Target, ExpandConstant('{commonpf64}')) or
     WithinDirectory(Target, ExpandConstant('{win}')) or
     (Length(RemoveBackslashUnlessRoot(Target)) <= 3) then
  begin
    MsgBox('这个位置需要额外权限，或属于磁盘根目录。请使用推荐的当前用户目录，或选择其他可写文件夹。', mbError, MB_OK);
    Result := False;
  end;
end;
