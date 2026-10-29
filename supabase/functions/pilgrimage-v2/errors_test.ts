import {errorCode} from './errors.ts';
Deno.test('recoverable media failure is distinct from duplicate business content',()=>{
 if(errorCode('上一次图片正在清理，请稍后手动重试',503)!=='media_retry')throw new Error('retry category');
 if(errorCode('重复内容',409)!=='duplicate_content')throw new Error('duplicate category');
 if(errorCode('云端图片已达100张',400)!=='image_limit')throw new Error('quota category');
 if(errorCode('账号最后清理步骤未完成，请重试注销',503)!=='deletion_retry')throw new Error('deletion category');
});
