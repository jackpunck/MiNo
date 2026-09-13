// 首屏字体引导。
//
// 设置存在 Rust 侧，只能通过异步 invoke 拿到 —— 等它回来时页面早就画完了，
// 会先闪一下默认字体。这里在 <head> 里同步读一份 localStorage 缓存，
// 抢在首次绘制前把字体链写进 CSS 变量。
//
// 这只是缓存，不是权威来源：app.js 拿到真实设置后会覆盖它并回写。
(function () {
  try {
    var chain = localStorage.getItem('mino.font');
    if (chain) {
      document.documentElement.style.setProperty('--note-font', chain);
    }
  } catch (e) {
    // localStorage 不可用时静默跳过，CSS 里的默认值仍然生效
  }
})();
