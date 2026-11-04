const fs=require('fs');
const path=require('path');
process.chdir(path.resolve(__dirname,'..'));
function option(name){const i=process.argv.indexOf(name);return i>=0?process.argv[i+1]:undefined}
const output=path.resolve(option('--out')||'.local-evidence/design-refresh/aiyou-skyglass.fragment.html');
const reference=option('--reference');
const sky='data:image/webp;base64,'+fs.readFileSync('design/assets/nature-themes/blue.webp').toString('base64');
const ref=reference?'data:image/'+(path.extname(reference).slice(1)==='jpg'?'jpeg':path.extname(reference).slice(1))+';base64,'+fs.readFileSync(reference).toString('base64'):'';
let html=fs.readFileSync('design/prototypes/skyglass-base.fragment.html','utf8').replaceAll('ay-journal','ay-sky').replaceAll("accent:'#42634d'","accent:'#3569d4'");
const css=fs.readFileSync('design/prototypes/skyglass.css','utf8').replaceAll('__SKY__',sky)+`\n#ay-sky .lulu-ref{background-image:url('${ref}');background-size:cover}#ay-sky .lulu-ref span{position:absolute;bottom:0;left:0;right:0;background:#ffffffcf;color:#546d95;font-size:9px;padding:2px}#ay-sky .ref-large{width:210px;height:210px;display:block;margin:20px auto;border-radius:18px}`;
html=html.replace('</style>',css+'\n</style>');
html=html.replace('<div class="status">','<div class="sky-art" aria-hidden="true"></div><div class="status">');
html=html.replace("if(b.dataset.page){page=b.dataset.page;render();return}","if(b.dataset.page){const pages=['now','memories','tasks'];phone.style.setProperty('--route-direction',pages.indexOf(b.dataset.page)>=pages.indexOf(page)?1:-1);page=b.dataset.page;phone.style.setProperty('--nav-index',pages.indexOf(page));render();return}");
html=html.replace("shade.addEventListener('click',close);",`let dragStart=null,dragDelta=0;root.addEventListener('pointerdown',e=>{const card=e.target.closest('.entry,.memory,.task');if(card){const r=card.getBoundingClientRect();card.style.setProperty('--touch-x',((e.clientX-r.left)/r.width*100)+'%');card.style.setProperty('--touch-y',((e.clientY-r.top)/r.height*100)+'%');card.classList.remove('touch-wave');void card.offsetWidth;card.classList.add('touch-wave');setTimeout(()=>card.classList.remove('touch-wave'),500)}if(e.target.closest('.handle')){dragStart=e.clientY;dragDelta=0;sheet.classList.add('dragging');e.target.setPointerCapture(e.pointerId)}});root.addEventListener('pointermove',e=>{if(dragStart!==null){dragDelta=Math.max(0,e.clientY-dragStart);sheet.style.transform='translateY('+dragDelta+'px)'}});function releaseDrag(){if(dragStart===null)return;dragStart=null;sheet.classList.remove('dragging');sheet.style.transform='';if(dragDelta>100)close();dragDelta=0}root.addEventListener('pointerup',releaseDrag);root.addEventListener('pointercancel',releaseDrag);shade.addEventListener('click',close);`);
const before="function intro(title,sub,eye){return `<div class=\"intro\"><div class=\"eyebrow\">${eye}</div><h1>${title}</h1><p class=\"soft\">${sub}</p>${icon('leaf').replace('class=\"icon\"','class=\"sprig\"')}</div>`}";
const after="function intro(title,sub,eye){return `<div class=\"intro\"><div class=\"eyebrow\">${eye}</div><h1>${page==='now'?'把日常，<br>收进小晴天。':title}</h1><p class=\"soft\">${page==='now'?'有噜噜陪着，慢慢记就好。':sub}</p><button class=\"lulu-ref\" data-action=\"companion\" aria-label=\"噜噜原版参考，造型校准中\"><span>原版参考 · 待校准</span></button></div>`}";
if(!html.includes(before))throw Error('intro anchor missing');html=html.replace(before,after);
html=html.replace("'<div style=\"padding:32px 0;color:var(--accent);text-align:center\">'+icon('sprout')+'</div>","'<img class=\"ref-large\" src=\""+ref+"\" alt=\"用户提供的噜噜原版参考\"><p class=\"soft\" style=\"text-align:center\">原版参考 · 新造型与动作正在校准</p>");
html=html.replace("render();if(globalThis.Tweak)","document.addEventListener('visibilitychange',()=>{phone.classList.toggle('quiet',document.hidden);if(document.hidden)stop()});render();if(globalThis.Tweak)");
html=html.replace("function close(){sheet.classList.remove('open');", "function close(){sheet.classList.add('leaving');sheet.classList.remove('open');");
html=html.replace("sheet.inert=false;shade.classList.add('open');", "sheet.classList.remove('leaving');sheet.inert=false;shade.classList.add('open');");
html=html.replace("state.added.unshift(t);state.draft='';b.textContent='记好了';", "b.disabled=true;state.added.unshift(t);state.draft='';b.textContent='记好了';");
html=html.replace('交互设计提案 · 合成内容与播放状态 · 可点击底栏、编辑、任务与设置','晴空玻璃 · 可点击交互提案 · 噜噜暂用你的原版参考图');
const themeImages=['blue','sunset','night'].map(n=>`--image-${n}:url('data:image/webp;base64,${fs.readFileSync('design/assets/nature-themes/'+n+'.webp').toString('base64')}')`).join(';');
html=html.replace('</style>',fs.readFileSync('design/prototypes/skyglass-themes.css','utf8').replace(/^@import[^\n]+\n/,'')+`\n#ay-sky .phone{${themeImages}}\n</style>`);
html=html.replace('<style>',`<style>@import url('https://fonts.googleapis.com/css2?family=Noto+Sans+SC:wght@400;500;600&display=swap');\n`);
html=html.replace('<div class="sky-art" aria-hidden="true"></div>',['blue','sunset','night'].map(n=>`<div class="sky-art" data-background="${n}" style="--landscape:var(--image-${n})" aria-hidden="true"></div>`).join(''));
html=html.replace('data-action="companion" aria-label="陪伴"','data-action="appearance" aria-label="外观"').replace('data-lucide="sprout"','data-lucide="palette"');
html=html.replaceAll('data-lucide="feather"','data-lucide="notebook-pen"').replaceAll('data-lucide="book-open"','data-lucide="book-open-text"').replaceAll('data-lucide="square-check"','data-lucide="list-checks"').replaceAll('data-lucide="settings-2"','data-lucide="settings"');
html=html.replace('<span>${2+state.added.length} 条记录</span>','').replaceAll('<time>16:24 · 随手记</time>','').replaceAll('<time>12:08 · 原音</time>','').replaceAll('<time>刚刚 · 文字</time>','');
html=html.replace('NOVEMBER · 2026','11 月').replace('YOUR LITTLE ARCHIVE','').replace('WEDNESDAY, NOV 04','11 月 04 日 · 星期三').replace('原始录音 · 00:12','00:12').replace('12:08　·　未转写','12:08');
html=html.replace('</script>',`</script><script>${fs.readFileSync('design/prototypes/skyglass-themes.js','utf8')}</script>`);

// Page structure is intentionally distinct; shared style does not duplicate welcome panels.
const renderStart=html.indexOf('function render(){'),renderEnd=html.indexOf('function stop(){',renderStart);
if(renderStart<0||renderEnd<0)throw Error('render anchor missing');
html=html.slice(0,renderStart)+fs.readFileSync('design/prototypes/skyglass-pages.js','utf8')+'\n'+html.slice(renderEnd);
html=html.replace('</style>',fs.readFileSync('design/prototypes/skyglass-layout.css','utf8')+'\n</style>');
const filterStart=html.indexOf('if(b.dataset.filter){'),switchStart=html.indexOf('switch(b.dataset.action)',filterStart);
if(filterStart<0||switchStart<0)throw Error('event anchor missing');
html=html.slice(0,filterStart)+`if(b.dataset.filter){state.filter=b.dataset.filter;root.querySelectorAll('[data-filter]').forEach(x=>{x.classList.toggle('selected',x===b);x.setAttribute('aria-pressed',String(x===b))});filterMemories();return}if(b.dataset.day){state.selectedDay=Number(b.dataset.day);render();root.querySelector('[data-day="'+state.selectedDay+'"]').focus();return}if(b.dataset.taskId){state.checked??={};const id=b.dataset.taskId;state.checked[id]=!state.checked[id];b.closest('.task').classList.toggle('done',state.checked[id]);b.setAttribute('aria-pressed',String(state.checked[id]));toast(state.checked[id]?'已完成 · 可再次点按撤销':'已恢复为待办');return}`+html.slice(switchStart);
html=html.replace("icons();setTimeout(()=>sheet.querySelector", "updateSubmit();icons();setTimeout(()=>sheet.querySelector");
html=html.replace("b.disabled=true;state.added.unshift(t);", "b.disabled=true;b.dataset.busy='true';state.added.unshift(t);");
html=html.replace("document.addEventListener('visibilitychange'", "root.addEventListener('input',e=>{updateSubmit();if(e.target.matches('.search input')){state.query=e.target.value;filterMemories()}});new ResizeObserver(placeNavIndicator).observe(root.querySelector('nav'));document.addEventListener('visibilitychange'");
html=html.replace('0:00 / 0:12','0:12');
html=html.replaceAll('lastFocus?.focus()','lastFocus?.focus({preventScroll:true})').replaceAll("sheet.querySelector('textarea,input,button')?.focus()","sheet.querySelector('textarea,input,button')?.focus({preventScroll:true})");

html=html.replace("switch(b.dataset.action)",`if(b.dataset.action==='new-task'){modal('添加待办','<textarea aria-label="待办标题" placeholder="想做些什么？"></textarea><div class="settings-row">日期<span>11 月 '+state.selectedDay+' 日</span></div>','<button class="solid" data-action="save-task">添加</button>');return}if(b.dataset.action==='save-task'){const field=sheet.querySelector('textarea'),value=field.value.trim();if(!value)return;b.disabled=true;state.extraTasks??=[];state.extraTasks.push({id:'demo-'+Date.now(),title:value,day:state.selectedDay});close();render();toast('已加入演示待办');return}if(b.dataset.action==='task-detail'&&b.closest('.task')){const row=b.closest('.task');modal('待办详情','<h2>'+safe(row.querySelector('.title').textContent)+'</h2><div class="settings-row">时间<span>'+safe(row.querySelector('.task-time')?.textContent||'未设时刻')+'</span></div><div class="options"><button data-action="close">返回列表</button></div>');return}switch(b.dataset.action)`);

html=html.replace('function stop(){',fs.readFileSync('design/prototypes/skyglass-polish.js','utf8')+'\nfunction stop(){');
html=html.replace('</style>',fs.readFileSync('design/prototypes/skyglass-polish.css','utf8')+'\n</style>');
html=html.replace("if(b.dataset.action==='new-task'){","if(handlePolishAction(b))return;if(b.dataset.action==='new-task'){");
html=html.replace("function close(){","function close(){rememberPanelDraft();");
html=html.replace("lastFocus=document.activeElement;sheet.innerHTML=","lastFocus=document.activeElement;sheet.dataset.panel=title==='记下此刻'?'record':'other';sheet.innerHTML=");
if(Buffer.byteLength(html)>1000000)throw Error('oversize');
if(!reference)html=html.replace('</style>',"#ay-sky .lulu-ref:after{content:'噜噜';color:var(--accent);font-size:16px}#ay-sky .ref-large{display:none}</style>");
fs.mkdirSync(path.dirname(output),{recursive:true});fs.writeFileSync(output,html);
console.log('Written',Buffer.byteLength(html),'bytes');
