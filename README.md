# WeRead Plugin for Halo

一个 Halo 博客插件，将您的微信读书（WeRead）数据同步展示到个人博客中。

## 核心特性

*   **全自动数据同步**: 依托于强大的后台逻辑，插件可自动从微信读书拉取最新的书架信息、阅读时长及详细进度。
*   **CookieCloud 深度集成**: 彻底解决手动更新 Cookie 的烦恼。支持配置 CookieCloud 服务端，实现凭证的自动化更新与端到端维护。
*   **高性能同步策略**: 采用全量书架预加载技术，有效减少 API 请求频次，提升同步效率并降低封禁风险。
*   **多维度元数据**: 同步包含作者、出版信息、ISBN、总字数、笔记数、评论数及分类在内的完整数据。

## 预览
设置界面
![setting.png](assets/setting.png)
书籍管理界面
![book.png](assets/book.png)
前端展示
![show.png](assets/show.png)

> [!NOTE]
> 前端展示效果建议配合适配的主题或自定义页面使用，以获得最佳视觉体验。

## 安装指南

### 环境要求
*   Halo 版本: >= 2.26.0

### 手动安装
1. 下载插件的 JAR 包（例如 `halo-weread-plugin-x.y.z.jar`）。
2. 进入 Halo 控制台 -> 插件 -> 安装。
3. 选择下载好的 JAR 文件上传并启用。

## 配置说明

启用插件后，请前往“插件配置”页面完成初始化：

### 凭证配置
本插件推荐使用 **CookieCloud** 实现凭证的自动获取与长期维护：
*   **自动获取**: 配置 CookieCloud 相关信息（API 地址、UUID、加密密码），插件将自动同步并维护您的微信读书凭证。

### 2. 数据同步
*   配置完成后，点击“启动数据拉取”即可立即触发同步。
*   插件会自动管理后续的数据更新。

## 技术亮点

*   **异步响应式架构**: 基于 Project Reactor 栈开发，确保在高并发数据同步时系统依然保持轻量且响应迅速。
*   **鲁棒的容错机制**: 内置 Cookie 自动刷新逻辑，当遇到 401 错误时自动尝试补全凭证，增强同步稳定性。
*   **精细化数据映射**: 针对不同类型的书籍 ID 实现了复杂的 URL 哈希映射算法。

## 前端集成

您可以将以下代码片段添加到您的主题模板或自定义页面中，以实现访客免登录查看书架：

```html
<!-- 微信读书书架容器 -->
<div id="weread-shelf-wrapper">加载中...</div>

<script type="module">
  const container = document.getElementById('weread-shelf-wrapper');

  try {
    const response = await fetch('/halo-weread-plugin/shelf-html');
    if (!response.ok) throw new Error('书架请求失败');

    container.innerHTML = await response.text();
    container.querySelectorAll('script').forEach((oldScript) => {
      const script = document.createElement('script');
      script.textContent = oldScript.textContent;
      oldScript.replaceWith(script);
    });
  } catch (error) {
    container.textContent = '书架暂时无法展开，请稍后再试。';
  }
</script>
```

## 常见问题 (FAQ)

**Q: 什么是 CookieCloud?**
A: CookieCloud 是一个简单易用的端到端加密 Cookie 同步工具。它可以配合浏览器插件，在您的浏览器登录状态更新时自动将最新凭证同步到 Halo 插件中，彻底解决微信读书凭证易失效的问题。

## 许可证

本项目基于 MIT 协议开源。详情请参阅 [LICENSE](LICENSE) 文件。
