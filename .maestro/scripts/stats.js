const response = http.get('http://127.0.0.1:18734/__state');
assert(response.status === 200, 'HTTP fixture stats missing');
const stats = JSON.parse(response.body);
output.originals = stats.originals;
output.lists = stats.lists;
output.leaks = stats.leaks;
assert(stats.leaks === 0, 'API credentials leaked into a media request');
