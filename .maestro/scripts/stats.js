const response = http.get('http://127.0.0.1:18734/__state');
if (Number(response.status) !== 200) throw new Error('HTTP fixture stats missing');
const stats = JSON.parse(response.body);
output.originals = stats.originals;
output.lists = stats.lists;
output.leaks = stats.leaks;
if (stats.leaks !== 0) throw new Error('API credentials leaked into a media request');
