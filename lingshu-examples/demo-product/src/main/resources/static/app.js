/* LingShu demo-product chat client (Story #025).
 *
 * Boots by reading sessionId from localStorage (or POSTing /api/sessions).
 * Each "Send" → POST /api/chat/{sid} as text/event-stream, parses SSE
 * events one by one, appends text deltas to the chat bubble and pushes
 * structured events into the right-hand panel.
 *
 * Zero dependencies — vanilla JS, no framework.
 */
(function () {
  var chatEl = document.getElementById('chat');
  var eventsEl = document.getElementById('events');
  var promptEl = document.getElementById('prompt');
  var sidEl = document.getElementById('sid');
  var sendBtn = document.getElementById('send');
  var newBtn = document.getElementById('new');

  var sessionId = localStorage.getItem('lingshu.sid');
  if (!sessionId) {
    fetch('/api/sessions', { method: 'POST' })
      .then(function (r) { return r.json(); })
      .then(function (j) {
        sessionId = j.sessionId;
        localStorage.setItem('lingshu.sid', sessionId);
        sidEl.textContent = 'session: ' + sessionId;
      });
  } else {
    sidEl.textContent = 'session: ' + sessionId;
  }

  newBtn.onclick = function () {
    fetch('/api/sessions', { method: 'POST' })
      .then(function (r) { return r.json(); })
      .then(function (j) {
        sessionId = j.sessionId;
        localStorage.setItem('lingshu.sid', sessionId);
        sidEl.textContent = 'session: ' + sessionId;
        chatEl.innerHTML = '';
        eventsEl.innerHTML = '';
      });
  };

  sendBtn.onclick = send;
  promptEl.onkeydown = function (e) {
    if (e.key === 'Enter') send();
  };

  function send() {
    var text = promptEl.value.trim();
    if (!text || !sessionId) return;
    promptEl.value = '';
    appendMsg('user', text);

    var bubble = appendMsg('assistant', '');
    var eventsPanel = eventsEl;

    fetch('/api/chat/' + sessionId, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
      body: JSON.stringify({ prompt: text })
    }).then(function (response) {
      if (!response.ok) {
        bubble.textContent = '[error] HTTP ' + response.status;
        return;
      }
      var reader = response.body.getReader();
      var decoder = new TextDecoder();
      var buf = '';

      function pump() {
        return reader.read().then(function (r) {
          if (r.done) return;
          buf += decoder.decode(r.value, { stream: true });
          var idx;
          while ((idx = buf.indexOf('\n\n')) !== -1) {
            var chunk = buf.slice(0, idx);
            buf = buf.slice(idx + 2);
            handleEvent(chunk, bubble, eventsPanel);
          }
          return pump();
        });
      }
      return pump();
    }).catch(function (err) {
      bubble.textContent += '\n[stream error] ' + err;
    });
  }

  function handleEvent(chunk, bubble, eventsPanel) {
    var eventName = 'message';
    var dataLines = [];
    chunk.split('\n').forEach(function (line) {
      if (line.indexOf('event:') === 0) eventName = line.slice(6).trim();
      else if (line.indexOf('data:') === 0) dataLines.push(line.slice(5).trim());
    });
    var data;
    try { data = JSON.parse(dataLines.join('\n')); } catch (e) { data = { raw: dataLines.join('\n') }; }

    // Render into the right panel.
    var ev = document.createElement('div');
    ev.className = 'ev ' + eventName.replace(/\./g, '-');
    ev.textContent = eventName + '  ' + JSON.stringify(data);
    eventsPanel.appendChild(ev);
    eventsPanel.scrollTop = eventsPanel.scrollHeight;

    // Stream text into the assistant bubble.
    if (eventName === 'text' && data.delta) {
      bubble.textContent += data.delta;
      chatEl.scrollTop = chatEl.scrollHeight;
    } else if (eventName === 'turn.completed') {
      bubble.textContent += '\n— turn completed (' + data.reason + ')';
    } else if (eventName === 'compacted') {
      bubble.textContent += '\n— compacted (≈' + (data.approxTokensFreed || 0) + ' tokens freed)';
    }
  }

  function appendMsg(role, text) {
    var m = document.createElement('div');
    m.className = 'msg ' + role;
    m.textContent = text;
    chatEl.appendChild(m);
    chatEl.scrollTop = chatEl.scrollHeight;
    return m;
  }
})();