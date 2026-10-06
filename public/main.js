document.addEventListener("DOMContentLoaded", () => {
  var __i18n = {
    copied: "已复制",
    copyFailed: "复制失败，请手动复制",
    networkError: "网络错误",
    cannotGetPwd: "无法获取密码",
    showPassword: "显示密码",
    hidePassword: "隐藏密码",
  };

  // ============================================================
  // Toast notification（静态 CSS，见 public/app.css 的 .pf-toast-*）
  // ============================================================
  function showToast(message, type) {
    type = type || "success";
    var container = document.getElementById("toast-container");
    if (!container) {
      container = document.createElement("div");
      container.id = "toast-container";
      container.className = "pf-toast-container";
      document.body.appendChild(container);
    }
    var variant = type === "error" ? "error" : type === "warning" ? "warning" : "success";
    var toast = document.createElement("div");
    toast.className = "pf-toast pf-toast--" + variant;
    var toastMessage = document.createElement("span");
    toastMessage.textContent = message;
    toast.appendChild(toastMessage);
    container.appendChild(toast);
    setTimeout(function () {
      toast.classList.add("pf-toast--leaving");
      setTimeout(function () {
        toast.remove();
      }, 300);
    }, 3000);
  }

  // ============================================================
  // Copy to clipboard
  // ============================================================
  function copyToClipboard(text) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(
        function () { showToast(__i18n.copied, "success"); },
        function () { showToast(__i18n.copyFailed, "error"); },
      );
    } else {
      var ta = document.createElement("textarea");
      ta.value = text;
      ta.style.position = "fixed";
      ta.style.opacity = "0";
      document.body.appendChild(ta);
      ta.select();
      try {
        document.execCommand("copy");
        showToast(__i18n.copied, "success");
      } catch (e) {
        showToast(__i18n.copyFailed, "error");
      }
      document.body.removeChild(ta);
    }
  }

  // ============================================================
  // SSE connection (Server-Sent Events, replaces WebSocket)
  // Browser handles reconnection automatically.
  // ============================================================
  var panelRefreshMap = {
    "forward-tbody": "refresh-forward",
    "caddy-tbody": "refresh-caddy",
    "docker-tbody": "refresh-docker",
    "smolvm-tbody": "refresh-smolvm",
    "quota-bar": "refresh-quota",
    "overview-stats": "refresh-overview",
    "topology-view": "refresh-topology",
    "port-spectrum": "refresh-spectrum",
    "activity-feed": "refresh-activity",
    "host-metrics": "refresh-host",
    "admin-users-tbody": "refresh-admin",
    "wall-kpis": "refresh-wall-kpis",
    "wall-topology": "refresh-wall-topology",
    "wall-alerts": "refresh-wall-alerts",
  };

  function connectSSE() {
    var ticketMeta = document.querySelector('meta[name="sse-ticket"]');
    if (!ticketMeta) {
      console.warn("[sse] 页面中未找到 sse-ticket meta 标签");
      return;
    }
    var ticket = ticketMeta.getAttribute("content");
    var es = new EventSource("/events?ticket=" + encodeURIComponent(ticket));

    es.onopen = function () {
      console.log("[sse] 已连接");
      document.body.dispatchEvent(new CustomEvent("sse-status", {detail: {state: "connected"}}));
    };

    es.onmessage = function (event) {
      try {
        var msg = JSON.parse(event.data);
        if (msg.event === "refresh" && msg.panels) {
          msg.panels.forEach(function (panelId) {
            var triggerName = panelRefreshMap[panelId];
            if (triggerName) {
              var el = document.getElementById(panelId);
              if (el && el.dataset.loaded !== "false") {
                htmx.trigger(el, triggerName);
              }
            }
          });
        } else if (msg.event === "stats" && msg.stats) {
          updateResourceBars(msg.stats);
        }
      } catch (e) {
        console.warn("[sse] 消息解析失败:", e);
      }
    };

    es.onerror = function () {
      // EventSource 会自动重连，无需手动处理
      console.log("[sse] 连接中断，浏览器将自动重连");
      document.body.dispatchEvent(new CustomEvent("sse-status", {detail: {state: "reconnecting"}}));
    };
  }

  connectSSE();

  function getCsrfToken() {
    var meta = document.querySelector('meta[name="csrf-token"]');
    return meta ? meta.getAttribute("content") : "";
  }

  function getCredentials(card) {
    var endpoint = card && card.getAttribute("data-credential-endpoint");
    var id = card && card.getAttribute("data-resource-id");
    var csrf = getCsrfToken();
    if (!endpoint || !id || !csrf) {
      return Promise.reject(new Error("凭据请求参数不完整"));
    }
    var body = new URLSearchParams();
    body.set("id", id);
    body.set("_csrf", csrf);
    return fetch(endpoint, {
      method: "POST",
      headers: {"Content-Type": "application/x-www-form-urlencoded", "X-CSRF-Token": csrf},
      body: body.toString(),
      credentials: "same-origin",
    }).then(function (res) {
      if (!res.ok) {
        throw new Error("凭据请求失败");
      }
      return res.json();
    }).then(function (data) {
      if (!data.shellPass) {
        throw new Error("凭据响应无密码");
      }
      return data; // 返回完整对象 {shellPass, shellUser, shellUrl}
    });
  }

  // 构造带凭据的 shell URL：http://user:pass@host:port 或 https://user:pass@host
  function buildShellUrl(baseUrl, user, pass) {
    try {
      var u = new URL(baseUrl);
      u.username = encodeURIComponent(user);
      u.password = encodeURIComponent(pass);
      return u.toString();
    } catch (e) {
      return baseUrl;
    }
  }

  // ============================================================
  // Listen to HTMX show-toast custom event
  // ============================================================
  document.body.addEventListener("show-toast", function (evt) {
    if (evt.detail && evt.detail.message) {
      showToast(evt.detail.message, evt.detail.type || "success");
    }
  });

  document.body.addEventListener("htmx:afterRequest", function (evt) {
    var form = evt.detail.elt;
    if (evt.detail.successful && form && form.getAttribute("data-reset-on-success") === "true") {
      form.reset();
    }
  });

  document.body.addEventListener("keydown", function (evt) {
    if (evt.key !== "Escape") {
      return;
    }
    var input = evt.target.closest("[data-remark-cancel]");
    if (!input) {
      return;
    }
    var form = input.closest("form");
    var params = new URLSearchParams();
    if (form) {
      form.querySelectorAll("input[name]").forEach(function (field) {
        if (field.name !== "_csrf" && field.name !== "remark") {
          params.set(field.name, field.value);
        }
      });
      params.set("remark", input.value);
    }
    htmx.ajax("GET", input.getAttribute("data-remark-cancel") + "?" + params.toString(), {
      target: input.closest("td"),
      swap: "innerHTML",
    });
  });

  // ============================================================
  // 容器日志面板：关闭按钮
  // ============================================================
  document.body.addEventListener("click", function (e) {
    if (e.target.closest('[data-action="logs.close"]')) {
      e.preventDefault();
      closeLogPanel();
    }
    if (e.target.closest('[data-action="terminal.close"]')) {
      e.preventDefault();
      var terminalModal = document.getElementById("terminalModal");
      if (terminalModal) {
        terminalModal.close();
      }
    }
  });

  // ============================================================
  // 容器日志面板：htmx 换入 <dialog> 后打开，轮询换入正文后按需滚到底部
  // ============================================================
  document.body.addEventListener("htmx:afterSwap", function (evt) {
    var target = evt.detail.target;
    if (target.id === "password-modal-container") {
      var modal = document.getElementById("passwordModal");
      if (modal) {
        modal.showModal();
      }
    }
    if (target.id === "log-panel-container") {
      var logModal = document.getElementById("logModal");
      if (logModal) {
        logModal.showModal();
        scrollLogToEnd();
      }
    }
    if (target.id === "terminal-modal-container") {
      var terminalModal = document.getElementById("terminalModal");
      if (terminalModal) {
        terminalModal.showModal();
      }
    }
    if (target.id === "log-stream") {
      scrollLogToEnd();
    }
  });

  function logFollowEnabled() {
    var follow = document.getElementById("log-follow");
    return !follow || follow.checked;
  }

  function scrollLogToEnd() {
    var box = document.getElementById("log-stream");
    if (box && logFollowEnabled()) {
      box.scrollTop = box.scrollHeight;
    }
  }

  function closeLogPanel() {
    var logModal = document.getElementById("logModal");
    if (logModal) {
      logModal.close();
    }
  }

  // 面板关闭（按钮/背景/Esc）后清空容器，停掉内部的轮询元素，避免隐藏面板继续请求。
  document.body.addEventListener("close", function (e) {
    if (e.target && e.target.id === "logModal") {
      var container = document.getElementById("log-panel-container");
      if (container) {
        container.innerHTML = "";
      }
    }
  }, true);

  // ============================================================
  // Password modal: close and refresh after password change
  // ============================================================
  document.body.addEventListener("closePasswordModal", function () {
    var modalEl = document.getElementById("passwordModal");
    if (modalEl) {
      modalEl.close();
    }
    setTimeout(function () {
      window.location.reload();
    }, 400);
  });

  // ============================================================
  // Global click delegation
  // ============================================================
  document.body.addEventListener("click", function (e) {
    // 1. Copy credentials
    var copyBtn = e.target.closest("[data-copy], [data-copy-pass]");
    if (copyBtn) {
      if (copyBtn.hasAttribute("data-copy")) {
        copyToClipboard(copyBtn.getAttribute("data-copy"));
      } else if (copyBtn.hasAttribute("data-copy-pass")) {
        var containerId = copyBtn.getAttribute("data-copy-pass");
        var card = copyBtn.closest(".card");
        var input = card.querySelector(".cred-pass-input");
        if (input && input.type === "text") {
          copyToClipboard(input.value);
          } else {
            getCredentials(copyBtn.closest("[data-credential-endpoint]"))
              .then(function (creds) { copyToClipboard(creds.shellPass); })
              .catch(() => showToast(__i18n.cannotGetPwd, "error"));
          }
      }
    }

    // 2. Toggle password visibility
    var togglePassBtn = e.target.closest("[data-container-id]");
    if (togglePassBtn && togglePassBtn.tagName === "BUTTON" && !copyBtn) {
      var containerId = togglePassBtn.getAttribute("data-container-id");
      var card = togglePassBtn.closest(".card");
      var input = card.querySelector(".cred-pass-input");
      if (input) {
        if (input.type === "password") {
          getCredentials(card)
            .then(function (creds) {
              input.type = "text";
              input.value = creds.shellPass;
              togglePassBtn.textContent = __i18n.hidePassword;
              togglePassBtn.title = __i18n.hidePassword;
            })
            .catch(() => showToast(__i18n.cannotGetPwd, "error"));
        } else {
          input.type = "password";
          input.value = "●●●●●●●●●●●●";
          togglePassBtn.textContent = __i18n.showPassword;
          togglePassBtn.title = __i18n.showPassword;
        }
      }
    }

    var closePasswordButton = e.target.closest("[data-close-password-modal]");
    if (closePasswordButton) {
      var passwordModal = document.getElementById("passwordModal");
      if (passwordModal) {
        passwordModal.close();
      }
    }

    // 3. WebShell 打开（带凭据）
    var shellOpenBtn = e.target.closest("[data-shell-open]");
    if (shellOpenBtn) {
      e.preventDefault();
      e.stopPropagation();
      var baseUrl = shellOpenBtn.getAttribute("data-shell-open");
      var shellUser = shellOpenBtn.getAttribute("data-shell-user");
      var card = shellOpenBtn.closest("[data-credential-endpoint]");
      // 先立即开窗（用户手势），再异步填充 URL，避免弹窗拦截
      var win = window.open("about:blank", "_blank", "width=1024,height=700,menubar=no,toolbar=no");
      if (!win) {
        showToast("弹窗被浏览器拦截，请允许弹出窗口后重试", "warning");
        return;
      }
      getCredentials(card)
        .then(function (creds) {
          win.location.href = buildShellUrl(baseUrl, shellUser || creds.shellUser, creds.shellPass);
        })
        .catch(function () {
          win.location.href = baseUrl;
        });
    }

    // 4. WebShell 复制带凭据的 URL
    var shellCopyBtn = e.target.closest("[data-shell-copy]");
    if (shellCopyBtn) {
      e.preventDefault();
      var baseUrl = shellCopyBtn.getAttribute("data-shell-copy");
      var shellUser = shellCopyBtn.getAttribute("data-shell-user");
      var card = shellCopyBtn.closest("[data-credential-endpoint]");
      getCredentials(card)
        .then(function (creds) {
          copyToClipboard(buildShellUrl(baseUrl, shellUser || creds.shellUser, creds.shellPass));
          showToast("已复制带凭据的 Shell URL", "success");
        })
        .catch(function () { showToast(__i18n.cannotGetPwd, "error"); });
    }
  });

  // ============================================================
  // Ensure HTMX includes CSRF token in headers
  // ============================================================
  document.body.addEventListener("htmx:configRequest", (event) => {
    var csrfMeta = document.querySelector('meta[name="csrf-token"]');
    var csrf = csrfMeta ? csrfMeta.getAttribute("content") : "";
    if (csrf) {
      event.detail.headers["X-CSRF-Token"] = csrf;
    }
  });

  // ============================================================
  // Container Resource Stats (via WebSocket push + daisyUI progress)
  // ============================================================

  function getResourceLevel(pct, bar) {
    bar.classList.remove("progress-info", "progress-success", "progress-warning", "progress-error");
    if (pct >= 80) {
      bar.classList.add("progress-error");
    } else if (pct >= 50) {
      bar.classList.add("progress-warning");
    } else {
      bar.classList.add("progress-success");
    }
  }

  function updateResourceBars(stats) {
    Object.keys(stats).forEach(function (containerId) {
      var s = stats[containerId];
      var row = document.querySelector('[data-container-id="' + containerId + '"]');
      if (!row) return;
      var cell = row.querySelector(".resource-usage-cell");
      if (!cell) return;

      var cpuBar = cell.querySelector(".resource-bar-cpu");
      var cpuText = cell.querySelector(".resource-bar-cpu + .resource-bar-text");
      var memBar = cell.querySelector(".resource-bar-mem");
      var memText = cell.querySelector(".resource-bar-mem + .resource-bar-text");

      if (cpuBar) {
        var cpuPct = Math.min(s["cpu-pct"] || 0, 100);
        cpuBar.value = cpuPct;
        getResourceLevel(cpuPct, cpuBar);
      }
      if (cpuText) {
        cpuText.textContent = (s["cpu-pct"] != null ? s["cpu-pct"].toFixed(1) : "—") + "%";
      }
      if (memBar) {
        var memPct = Math.min(s["mem-pct"] || 0, 100);
        memBar.value = memPct;
        getResourceLevel(memPct, memBar);
      }
      if (memText) {
        var memInfo = s["mem-used"] && s["mem-used"] !== "—"
          ? s["mem-used"] + " / " + s["mem-limit"]
          : "—";
        memText.textContent = memInfo;
      }
    });
  }

  // Fallback: fetch stats once after docker-tbody HTMX swap
  document.body.addEventListener("htmx:afterSwap", function (evt) {
    if (evt.detail.target.id === "docker-tbody") {
      fetch("/docker/stats")
        .then(function (res) {
          if (!res.ok) throw new Error("stats fetch failed");
          return res.json();
        })
        .then(function (stats) {
          updateResourceBars(stats);
        })
        .catch(function () {
          // Silently ignore — WS stats push will update soon
        });
    }
  });

});
