// Design-prototype interactions only. No storage, native permissions or network calls.
state.appliedFilters??={day:'all',person:'all',tag:'all'};
state.onlyFavorites??=false;
state.taskDraft??={text:'',day:4};
let filterDraft=null;
function rememberPanelDraft(){
 if(sheet.dataset.panel==='task')state.taskDraft={text:sheet.querySelector('textarea')?.value||'',day:Number(sheet.querySelector('[name="task-day"]')?.value||state.selectedDay)};
 filterDraft=null;
}
function autoGrowTask(){const field=sheet.querySelector('.task-title-input');if(field){field.style.height='auto';field.style.height=Math.max(72,Math.min(156,field.scrollHeight))+'px'}}
function showFilterPanel(){
 filterDraft={...state.appliedFilters};
 const select=(name,label,options)=>`<label class="filter-field"><span>${label}</span><select name="filter-${name}" aria-label="${label}筛选">${options.map(([v,t])=>`<option value="${v}" ${filterDraft[name]===v?'selected':''}>${t}</option>`).join('')}</select>${icon('chevron-down')}</label>`;
 modal('筛选回忆',select('day','日期',[['all','全部时间'],['4','11月4日'],['2','11月2日']])+select('person','人物',[['all','不限'],['none','未标注人物']])+select('tag','标签',[['all','全部标签'],['生活','生活'],['计划','计划'],['读书','读书']])+`<div class="filter-footer"><button data-action="reset-filters">重置</button><button class="solid" data-action="apply-filters">查看结果</button></div>`);
 sheet.dataset.panel='filters';
}
function handlePolishAction(button){
 const action=button.dataset.action;
 if(action==='filters'){showFilterPanel();return true}
 if(action==='reset-filters'){filterDraft={day:'all',person:'all',tag:'all'};sheet.querySelectorAll('select').forEach(s=>s.value='all');return true}
 if(action==='apply-filters'){
  const selected=Object.fromEntries([...sheet.querySelectorAll('select')].map(s=>[s.name.replace('filter-',''),s.value]));
  state.appliedFilters=selected;close();filterMemories();return true;
 }
 if(action==='toggle-favorites'){state.onlyFavorites=!state.onlyFavorites;button.setAttribute('aria-pressed',String(state.onlyFavorites));filterMemories();return true}
 if(action==='new-task'){
  const draft=state.taskDraft;
  modal('添加待办',`<textarea class="task-title-input" rows="2" aria-label="待办标题" placeholder="想做些什么？">${safe(draft.text)}</textarea><label class="filter-field"><span>日期</span><select name="task-day" aria-label="待办日期">${[2,3,4,5,6,7,8].map(day=>`<option value="${day}" ${day===(draft.text?draft.day:state.selectedDay)?'selected':''}>11月${day}日</option>`).join('')}</select>${icon('chevron-down')}</label>`,'<button class="solid" data-action="save-task">添加</button>');
  sheet.dataset.panel='task';autoGrowTask();return true;
 }
 if(action==='save-task'){
  const title=sheet.querySelector('textarea').value.trim();if(!title)return true;
  button.disabled=true;const day=Number(sheet.querySelector('[name="task-day"]').value);
  state.extraTasks??=[];state.extraTasks.push({id:'demo-'+Date.now(),title,day});state.selectedDay=day;state.taskDraft={text:'',day};
  sheet.dataset.panel='submitted';close();render();toast('已加入演示待办');return true;
 }
 return false;
}
root.addEventListener('input',autoGrowTask);
// Keep the opened search tools in place until leaving the page. Collapsing on
// blur can move media tabs between pointer-down and pointer-up and lose a tap.
root.addEventListener('focusin',e=>{if(e.target.matches('.search input'))e.target.closest('.search-region').classList.add('search-open')});
